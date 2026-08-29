package com.github.unscientificjszhai.idecontextforcodex.ipc.router

import com.github.unscientificjszhai.idecontextforcodex.ipc.IdeContextProvider
import com.github.unscientificjszhai.idecontextforcodex.ipc.transport.UnsafeIpcEndpointException
import com.intellij.openapi.diagnostic.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min
import kotlin.random.Random

/**
 * 固定采用“先连接兼容 Router，确认不存在后再竞选 owner”的平台无关协调顺序。
 */
class IpcRouterCoordinator(
    private val platform: IpcRouterPlatform,
    provider: IdeContextProvider,
) : AutoCloseable {
    private val routerClient = CodexRouterClient(provider)
    private val closed = AtomicBoolean(false)
    private val lifecycleLock = Any()

    internal val isProviderConnected: Boolean
        get() = routerClient.isInitialized

    @Volatile
    private var ownedRouter: JetBrainsIpcRouter? = null

    @Volatile
    private var ownerSession: IpcRouterOwnerSession? = null

    suspend fun run() {
        var retryDelayMs = INITIAL_RETRY_MS
        while (!closed.get()) {
            currentCoroutineContext().ensureActive()
            when (val result = connectExisting()) {
                is IpcConnectResult.Connected -> {
                    try {
                        routerClient.serve(result.connection)
                    } catch (exception: CancellationException) {
                        throw exception
                    } catch (_: IOException) {
                        // 现有 Router 断开后重新发现或竞选。
                    }
                    retryDelayMs = INITIAL_RETRY_MS
                    continue
                }

                IpcConnectResult.Absent -> {
                    if (tryBecomeOwner()) {
                        retryDelayMs = INITIAL_RETRY_MS
                        continue
                    }
                }

                IpcConnectResult.RetryableConflict -> Unit
            }
            if (closed.get()) break

            delay(retryDelayMs + Random.nextLong(0, JITTER_MS + 1))
            retryDelayMs = min(retryDelayMs * 2, MAX_RETRY_MS)
        }
    }

    private suspend fun connectExisting(): IpcConnectResult = try {
        platform.connectExisting()
    } catch (exception: UnsafeIpcEndpointException) {
        LOG.warn("拒绝不安全的 IPC endpoint：${exception.javaClass.simpleName}")
        IpcConnectResult.RetryableConflict
    } catch (exception: CancellationException) {
        throw exception
    } catch (exception: IOException) {
        LOG.warn("IPC Router 连接失败：${exception.javaClass.simpleName}")
        IpcConnectResult.RetryableConflict
    }

    private suspend fun tryBecomeOwner(): Boolean {
        val session = try {
            platform.tryAcquireOwner()
        } catch (exception: UnsafeIpcEndpointException) {
            LOG.warn(
                "拒绝取得不安全的 IPC Router ownership：${exception.javaClass.simpleName}",
            )
            return false
        } catch (exception: IOException) {
            LOG.warn("无法取得 IPC Router ownership：${exception.javaClass.simpleName}")
            return false
        } ?: return false

        var router: JetBrainsIpcRouter? = null
        try {
            val sessionPublished = synchronized(lifecycleLock) {
                if (closed.get()) {
                    false
                } else {
                    ownerSession = session
                    true
                }
            }
            if (!sessionPublished) return false

            router = JetBrainsIpcRouter(session.transport)
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
            routerClient.serve(session.connectProvider())
            return true
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: IOException) {
            LOG.warn("IPC Router owner 会话结束：${exception.javaClass.simpleName}")
            return false
        } finally {
            // close() 可能先清空共享字段；局部资源仍必须无条件释放。
            router?.close()
            session.close()
            synchronized(lifecycleLock) {
                if (ownedRouter === router) ownedRouter = null
                if (ownerSession === session) ownerSession = null
            }
        }
    }

    override fun close() {
        val resources = synchronized(lifecycleLock) {
            closed.set(true)
            val current = ownedRouter to ownerSession
            ownedRouter = null
            ownerSession = null
            current
        }
        resources.first?.close()
        resources.second?.close()
        routerClient.close()
        platform.close()
    }

    private companion object {
        val LOG: Logger = Logger.getInstance(IpcRouterCoordinator::class.java)
        const val INITIAL_RETRY_MS = 250L
        const val MAX_RETRY_MS = 5_000L
        const val JITTER_MS = 250L
    }
}
