package com.github.unscientificjszhai.codexjetbrainsideplugin.ipc.router

import com.github.unscientificjszhai.codexjetbrainsideplugin.ipc.IdeContextProvider
import com.github.unscientificjszhai.codexjetbrainsideplugin.ipc.IpcEndpoints
import com.github.unscientificjszhai.codexjetbrainsideplugin.ipc.transport.RouterOwnership
import com.github.unscientificjszhai.codexjetbrainsideplugin.ipc.transport.UnixEndpointSecurity
import com.github.unscientificjszhai.codexjetbrainsideplugin.ipc.transport.UnixIpcConnection
import com.github.unscientificjszhai.codexjetbrainsideplugin.ipc.transport.UnsafeIpcEndpointException
import com.intellij.openapi.diagnostic.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min
import kotlin.random.Random

/**
 * 固定采用“先连接兼容 Router，确认不存在后再安全 bind”的协调顺序。
 */
class IpcRouterCoordinator(
    private val endpoints: IpcEndpoints,
    private val provider: IdeContextProvider,
    private val coroutineScope: CoroutineScope,
    private val security: UnixEndpointSecurity = UnixEndpointSecurity(),
    private val endpointProbe: suspend (Path) -> Unit = { endpoint ->
        UnixIpcConnection.connect(endpoint).use { }
    },
) : AutoCloseable {
    private val routerClient = CodexRouterClient(provider)
    private val closed = AtomicBoolean(false)
    private val lifecycleLock = Any()

    internal val isProviderConnected: Boolean
        get() = routerClient.isInitialized

    @Volatile
    private var ownedRouter: JetBrainsIpcRouter? = null

    @Volatile
    private var ownership: RouterOwnership? = null

    suspend fun run() {
        var retryDelayMs = INITIAL_RETRY_MS
        while (!closed.get()) {
            currentCoroutineContext().ensureActive()
            val connected = tryExistingRouters()
            if (connected) {
                retryDelayMs = INITIAL_RETRY_MS
                continue
            }
            if (closed.get()) break

            val becameOwner = tryBecomeOwner()
            if (becameOwner) {
                retryDelayMs = INITIAL_RETRY_MS
                continue
            }
            if (closed.get()) break

            delay(retryDelayMs + Random.nextLong(0, JITTER_MS + 1))
            retryDelayMs = min(retryDelayMs * 2, MAX_RETRY_MS)
        }
    }

    private suspend fun tryExistingRouters(): Boolean {
        for (endpoint in endpoints.candidates) {
            if (!Files.exists(endpoint, NOFOLLOW_LINKS)) continue
            try {
                security.ensurePrivateDirectory(endpoint.parent)
                security.verifyExistingSocket(endpoint)
                routerClient.serve(endpoint)
                return true
            } catch (exception: UnsafeIpcEndpointException) {
                LOG.warn("拒绝连接不安全的 IPC endpoint：${exception.message}")
                if (endpoint == endpoints.primary) return false
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: IOException) {
                // 连接失败后回到 owner 竞选；不会在这里删除 endpoint。
                continue
            }
        }
        return false
    }

    private suspend fun tryBecomeOwner(): Boolean {
        val acquired = try {
            security.acquireOwnership(endpoints.ownershipLock)
        } catch (exception: IOException) {
            LOG.warn("无法取得 IPC Router ownership：${exception.message}")
            return false
        } ?: return false

        var router: JetBrainsIpcRouter? = null
        try {
            val ownershipPublished = synchronized(lifecycleLock) {
                if (closed.get()) {
                    false
                } else {
                    ownership = acquired
                    true
                }
            }
            if (!ownershipPublished) return false

            if (Files.exists(endpoints.primary, NOFOLLOW_LINKS)) {
                val fileKey = try {
                    security.verifyExistingSocket(endpoints.primary)
                } catch (exception: UnsafeIpcEndpointException) {
                    LOG.warn("拒绝清理不安全的 IPC endpoint：${exception.message}")
                    return false
                }

                if (probe(endpoints.primary)) {
                    return false
                }
                val staleSocketRemoved = synchronized(lifecycleLock) {
                    if (closed.get()) {
                        false
                    } else {
                        security.removeVerifiedStaleSocket(endpoints.primary, fileKey)
                        true
                    }
                }
                if (!staleSocketRemoved) return false
            }

            router = JetBrainsIpcRouter(
                endpoint = endpoints.primary,
                coroutineScope = coroutineScope,
                security = security,
            )
            val routerPublished = synchronized(lifecycleLock) {
                if (closed.get()) {
                    false
                } else {
                    ownedRouter = router
                    true
                }
            }
            if (!routerPublished) return false

            router.start()
            if (closed.get()) return false
            routerClient.serve(endpoints.primary)
            return true
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: IOException) {
            LOG.warn("IPC Router owner 会话结束：${exception.javaClass.simpleName}")
            return false
        } finally {
            // 共享字段可能已被 close() 清空；局部资源仍必须无条件释放。
            router?.close()
            acquired.close()
            synchronized(lifecycleLock) {
                if (ownedRouter === router) ownedRouter = null
                if (ownership === acquired) ownership = null
            }
        }
    }

    private suspend fun probe(endpoint: Path): Boolean = try {
        endpointProbe(endpoint)
        true
    } catch (_: ConnectException) {
        false
    } catch (_: NoSuchFileException) {
        false
    } catch (exception: SocketException) {
        val message = exception.message.orEmpty().lowercase(Locale.ROOT)
        if ("connection refused" in message || "no such file" in message) {
            false
        } else {
            throw exception
        }
    }

    override fun close() {
        val resources = synchronized(lifecycleLock) {
            closed.set(true)
            val current = ownedRouter to ownership
            ownedRouter = null
            ownership = null
            current
        }
        resources.first?.close()
        resources.second?.close()
        routerClient.close()
    }

    companion object {
        private val LOG = Logger.getInstance(IpcRouterCoordinator::class.java)
        private const val INITIAL_RETRY_MS = 250L
        private const val MAX_RETRY_MS = 5_000L
        private const val JITTER_MS = 250L
    }
}
