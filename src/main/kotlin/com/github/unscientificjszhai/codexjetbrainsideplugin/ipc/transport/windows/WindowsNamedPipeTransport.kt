package com.github.unscientificjszhai.codexjetbrainsideplugin.ipc.transport.windows

import com.github.unscientificjszhai.codexjetbrainsideplugin.ipc.protocol.IpcConstants
import com.github.unscientificjszhai.codexjetbrainsideplugin.ipc.transport.IpcConnection
import com.github.unscientificjszhai.codexjetbrainsideplugin.ipc.transport.IpcTransport
import com.github.unscientificjszhai.codexjetbrainsideplugin.ipc.transport.UnsafeIpcEndpointException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Windows named-pipe Router server。
 *
 * accept 前先创建备用监听实例，因此 timeout、拒绝对端和连接交接期间始终保留 endpoint owner。
 */
class WindowsNamedPipeTransport(
    initialListener: WindowsPipeHandle,
    private val adapter: WindowsNativePipeAdapter,
    private val coroutineScope: CoroutineScope,
    private val maxConnections: Int = 16,
) : IpcTransport {
    private val lifecycleLock = Any()
    private val listenersAndConnections = ConcurrentHashMap.newKeySet<WindowsPipeHandle>()
    private val connections = ConcurrentHashMap.newKeySet<WindowsNamedPipeConnection>()
    private val permits = Semaphore(maxConnections)
    private val running = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)

    @Volatile
    private var initialListener: WindowsPipeHandle? = initialListener

    @Volatile
    private var acceptJob: Job? = null

    override val isRunning: Boolean
        get() = running.get()

    init {
        require(maxConnections > 0) { "最大连接数必须大于 0" }
        listenersAndConnections += initialListener
    }

    override suspend fun start(handler: suspend (IpcConnection) -> Unit) {
        synchronized(lifecycleLock) {
            if (running.get()) return
            if (closed.get()) throw IOException("Windows named-pipe transport 已关闭")
            val firstListener = initialListener
                ?: throw IOException("Windows named-pipe transport 缺少初始监听实例")
            initialListener = null
            running.set(true)
            acceptJob = coroutineScope.launch {
                acceptLoop(firstListener, handler)
            }
        }
    }

    private suspend fun acceptLoop(
        firstListener: WindowsPipeHandle,
        handler: suspend (IpcConnection) -> Unit,
    ) {
        var current: WindowsPipeHandle? = firstListener
        try {
            while (running.get() && current != null) {
                val standby = createStandby()
                try {
                    adapter.awaitClient(current, IpcConstants.REQUEST_TIMEOUT_MS)
                } catch (_: WindowsPipeTimeoutException) {
                    closeHandle(current)
                    current = standby
                    continue
                } catch (exception: CancellationException) {
                    throw exception
                } catch (exception: IOException) {
                    closeHandle(current)
                    if (!running.get()) break
                    current = standby
                    continue
                }

                try {
                    adapter.verifyPeer(current, WindowsPipePeer.CLIENT)
                } catch (exception: UnsafeIpcEndpointException) {
                    closeHandle(current)
                    current = standby
                    continue
                }

                if (!permits.tryAcquire()) {
                    closeHandle(current)
                    current = standby
                    continue
                }

                val acceptedHandle = current
                lateinit var connection: WindowsNamedPipeConnection
                connection = WindowsNamedPipeConnection(
                    handle = acceptedHandle,
                    adapter = adapter,
                    serverSide = true,
                    onClose = {
                        connections -= connection
                        listenersAndConnections -= acceptedHandle
                        permits.release()
                    },
                )
                val connectionPublished = synchronized(lifecycleLock) {
                    if (running.get() && !closed.get() && acceptedHandle.isOpen) {
                        connections += connection
                        true
                    } else {
                        false
                    }
                }
                if (!connectionPublished) {
                    connection.close()
                    current = standby
                    continue
                }
                coroutineScope.launch {
                    try {
                        handler(connection)
                    } catch (exception: CancellationException) {
                        throw exception
                    } catch (_: IOException) {
                        // broken pipe、EOF 和本地 close 都是连接终态。
                    } finally {
                        connection.close()
                    }
                }
                current = standby
            }
        } finally {
            if (running.getAndSet(false)) {
                close()
            }
        }
    }

    private fun createStandby(): WindowsPipeHandle {
        val handle = when (val result = adapter.createServer(firstInstance = false)) {
            is WindowsServerCreateResult.Created -> result.handle

            WindowsServerCreateResult.Conflict ->
                throw IOException("Windows named-pipe owner 无法创建备用实例")
        }
        val published = synchronized(lifecycleLock) {
            if (running.get() && !closed.get()) {
                listenersAndConnections += handle
                true
            } else {
                false
            }
        }
        if (!published) {
            runCatching { handle.close() }
            throw IOException("Windows named-pipe transport 已关闭")
        }
        return handle
    }

    private fun closeHandle(handle: WindowsPipeHandle) {
        listenersAndConnections -= handle
        runCatching { adapter.disconnectServer(handle) }
        runCatching { handle.close() }
    }

    override fun close() {
        val resources = synchronized(lifecycleLock) {
            if (!closed.compareAndSet(false, true)) return
            running.set(false)
            val snapshot = CloseResources(
                acceptJob = acceptJob,
                connections = connections.toList(),
                handles = listenersAndConnections.toList(),
            )
            acceptJob = null
            connections.clear()
            listenersAndConnections.clear()
            snapshot
        }
        resources.acceptJob?.cancel()
        resources.connections.forEach(WindowsNamedPipeConnection::close)
        resources.handles.forEach(::closeHandle)
    }

    private data class CloseResources(
        val acceptJob: Job?,
        val connections: List<WindowsNamedPipeConnection>,
        val handles: List<WindowsPipeHandle>,
    )
}
