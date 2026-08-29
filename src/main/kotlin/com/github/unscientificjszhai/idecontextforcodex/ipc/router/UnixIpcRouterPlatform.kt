package com.github.unscientificjszhai.idecontextforcodex.ipc.router

import com.github.unscientificjszhai.idecontextforcodex.ipc.IpcEndpoints
import com.github.unscientificjszhai.idecontextforcodex.ipc.transport.*
import kotlinx.coroutines.CoroutineScope
import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Unix endpoint、stale socket 和进程锁的 Router 平台实现。
 */
class UnixIpcRouterPlatform(
    private val endpoints: IpcEndpoints,
    private val coroutineScope: CoroutineScope,
    private val security: UnixEndpointSecurity = UnixEndpointSecurity(),
    private val endpointProbe: suspend (Path) -> Unit = { endpoint ->
        UnixIpcConnection.connect(endpoint).use { }
    },
) : IpcRouterPlatform {
    private val lifecycleLock = Any()
    private val closed = AtomicBoolean(false)
    private var inFlightOwnership: RouterOwnership? = null

    override suspend fun connectExisting(): IpcConnectResult {
        if (closed.get()) return IpcConnectResult.RetryableConflict
        for (endpoint in endpoints.candidates) {
            if (!Files.exists(endpoint, NOFOLLOW_LINKS)) continue
            try {
                security.ensurePrivateDirectory(endpoint.parent)
                security.verifyExistingSocket(endpoint)
                return IpcConnectResult.Connected(UnixIpcConnection.connect(endpoint))
            } catch (exception: UnsafeIpcEndpointException) {
                // primary 不安全时禁止抢占；不可信 legacy 保持旧行为并跳过。
                if (endpoint == endpoints.primary) throw exception
                continue
            } catch (_: IOException) {
                // owner 流程会在持有进程锁后重新验证并探测 stale endpoint。
                continue
            }
        }
        return IpcConnectResult.Absent
    }

    override suspend fun tryAcquireOwner(): IpcRouterOwnerSession? {
        if (closed.get()) return null
        val ownership = security.acquireOwnership(endpoints.ownershipLock) ?: return null
        val ownershipPublished = synchronized(lifecycleLock) {
            if (closed.get()) {
                false
            } else {
                inFlightOwnership = ownership
                true
            }
        }
        if (!ownershipPublished) {
            ownership.close()
            return null
        }
        var ownershipTransferred = false
        try {
            if (closed.get()) return null
            if (Files.exists(endpoints.primary, NOFOLLOW_LINKS)) {
                val fileKey = security.verifyExistingSocket(endpoints.primary)
                if (probe(endpoints.primary)) {
                    return null
                }
                val staleRemoved = synchronized(lifecycleLock) {
                    if (closed.get()) {
                        false
                    } else {
                        security.removeVerifiedStaleSocket(endpoints.primary, fileKey)
                        true
                    }
                }
                if (!staleRemoved) return null
            }

            val transport = UnixDomainSocketTransport(
                endpoint = endpoints.primary,
                coroutineScope = coroutineScope,
                security = security,
            )
            if (closed.get()) {
                transport.close()
                return null
            }
            val canTransferOwnership = synchronized(lifecycleLock) {
                if (closed.get()) {
                    false
                } else {
                    if (inFlightOwnership === ownership) {
                        inFlightOwnership = null
                    }
                    true
                }
            }
            if (!canTransferOwnership) {
                transport.close()
                return null
            }
            ownershipTransferred = true
            return UnixRouterOwnerSession(
                endpoint = endpoints.primary,
                transport = transport,
                ownership = ownership,
                security = security,
            )
        } finally {
            if (!ownershipTransferred) {
                synchronized(lifecycleLock) {
                    if (inFlightOwnership === ownership) {
                        inFlightOwnership = null
                    }
                }
                ownership.close()
            }
        }
    }

    override fun close() {
        val ownership = synchronized(lifecycleLock) {
            closed.set(true)
            inFlightOwnership.also { inFlightOwnership = null }
        }
        ownership?.close()
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
}

private class UnixRouterOwnerSession(
    private val endpoint: Path,
    override val transport: IpcTransport,
    private val ownership: RouterOwnership,
    private val security: UnixEndpointSecurity,
) : IpcRouterOwnerSession {
    override suspend fun connectProvider(): IpcConnection =
        UnixIpcConnection.connect(endpoint, security = security)

    override fun close() {
        transport.close()
        ownership.close()
    }
}
