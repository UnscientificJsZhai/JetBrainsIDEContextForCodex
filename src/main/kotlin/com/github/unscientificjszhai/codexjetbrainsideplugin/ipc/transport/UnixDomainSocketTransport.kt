package com.github.unscientificjszhai.codexjetbrainsideplugin.ipc.transport

import com.github.unscientificjszhai.codexjetbrainsideplugin.ipc.protocol.LengthPrefixedJsonCodec
import com.github.unscientificjszhai.codexjetbrainsideplugin.ipc.protocol.SuspendByteChannel
import com.google.gson.JsonObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean

class UnixDomainSocketTransport(
    private val endpoint: Path,
    private val coroutineScope: CoroutineScope,
    private val security: UnixEndpointSecurity = UnixEndpointSecurity(),
    private val maxConnections: Int = 16,
    private val codec: LengthPrefixedJsonCodec = LengthPrefixedJsonCodec(),
) : IpcTransport {
    private val lifecycleMutex = Mutex()
    private val startCloseLock = Any()
    private val connections = ConcurrentHashMap.newKeySet<UnixIpcConnection>()
    private val permits = Semaphore(maxConnections)
    private val running = AtomicBoolean(false)

    @Volatile
    private var server: ServerSocketChannel? = null

    @Volatile
    private var startingServer: ServerSocketChannel? = null

    @Volatile
    private var acceptJob: Job? = null

    @Volatile
    private var endpointFileKey: Any? = null

    private val closeRequested = AtomicBoolean(false)
    private val permanentlyClosed = AtomicBoolean(false)

    override val isRunning: Boolean
        get() = running.get()

    init {
        require(maxConnections > 0) { "最大连接数必须大于 0" }
    }

    override suspend fun start(handler: suspend (IpcConnection) -> Unit) {
        lifecycleMutex.withLock {
            if (running.get()) return
            synchronized(startCloseLock) {
                if (permanentlyClosed.get()) {
                    throw IOException("IPC transport 已永久关闭")
                }
                closeRequested.set(false)
            }
            security.ensurePrivateDirectory(endpoint.parent)
            if (Files.exists(endpoint, NOFOLLOW_LINKS)) {
                throw IOException("IPC endpoint 已存在")
            }

            val newServer = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
            val serverPublished = synchronized(startCloseLock) {
                if (closeRequested.get() || permanentlyClosed.get()) {
                    false
                } else {
                    startingServer = newServer
                    true
                }
            }
            if (!serverPublished) {
                newServer.close()
                throw IOException("IPC transport 在启动期间被关闭")
            }
            var boundFileKey: Any? = null
            try {
                withContext(NonCancellable + Dispatchers.IO) {
                    newServer.bind(UnixDomainSocketAddress.of(endpoint))
                    boundFileKey = Files.readAttributes(
                        endpoint,
                        BasicFileAttributes::class.java,
                        NOFOLLOW_LINKS,
                    ).fileKey()
                    security.setSocketPermissions(endpoint)
                }
                if (closeRequested.get() || permanentlyClosed.get()) {
                    throw IOException("IPC transport 在启动期间被关闭")
                }
                endpointFileKey = boundFileKey
                server = newServer
                running.set(true)
                acceptJob = coroutineScope.launch(Dispatchers.IO) {
                    acceptLoop(newServer, handler)
                }
            } catch (exception: Throwable) {
                runCatching { newServer.close() }
                cleanupEndpoint(boundFileKey)
                throw exception
            } finally {
                if (startingServer === newServer) {
                    startingServer = null
                }
            }
        }
    }

    private suspend fun acceptLoop(
        listeningServer: ServerSocketChannel,
        handler: suspend (IpcConnection) -> Unit,
    ) {
        try {
            while (coroutineScope.isActive && listeningServer.isOpen) {
                val socket = runInterruptible(Dispatchers.IO) {
                    listeningServer.accept()
                }
                try {
                    security.verifyPeer(socket)
                } catch (exception: UnsafeIpcEndpointException) {
                    socket.close()
                    continue
                }
                if (!permits.tryAcquire()) {
                    socket.close()
                    continue
                }

                val connection = UnixIpcConnection(socket, codec)
                connections += connection
                coroutineScope.launch {
                    try {
                        handler(connection)
                    } catch (exception: CancellationException) {
                        throw exception
                    } catch (_: IOException) {
                        // EOF、对端关闭和本地 close 都属于正常连接终态。
                    } finally {
                        connections -= connection
                        connection.close()
                        permits.release()
                    }
                }
            }
        } catch (exception: IOException) {
            if (running.get() && listeningServer.isOpen) {
                throw exception
            }
        } finally {
            if (running.getAndSet(false)) {
                runCatching { listeningServer.close() }
                connections.toList().forEach(UnixIpcConnection::close)
                connections.clear()
                server = null
                cleanupOwnedEndpoint()
            }
        }
    }

    override fun close() {
        synchronized(startCloseLock) {
            closeRequested.set(true)
        }
        runCatching { startingServer?.close() }
        if (!running.getAndSet(false) && server == null) return
        val job = acceptJob
        acceptJob = null
        runCatching { server?.close() }
        server = null
        connections.toList().forEach(UnixIpcConnection::close)
        connections.clear()
        job?.cancel()
        cleanupOwnedEndpoint()
    }

    /**
     * 永久关闭当前实例；与普通 close() 不同，后续 start() 不会重新监听。
     */
    fun closePermanently() {
        synchronized(startCloseLock) {
            permanentlyClosed.set(true)
            closeRequested.set(true)
        }
        close()
    }

    suspend fun closeAndJoin() {
        val job = acceptJob
        close()
        job?.cancelAndJoin()
    }

    private fun cleanupOwnedEndpoint() {
        val expectedFileKey = endpointFileKey
        endpointFileKey = null
        cleanupEndpoint(expectedFileKey)
    }

    private fun cleanupEndpoint(expectedFileKey: Any?) {
        if (security.sameFile(endpoint, expectedFileKey)) {
            runCatching { Files.deleteIfExists(endpoint) }
        }
    }
}

class UnixIpcConnection internal constructor(
    private val socket: SocketChannel,
    private val codec: LengthPrefixedJsonCodec = LengthPrefixedJsonCodec(),
) : IpcConnection {
    private val channel = SocketSuspendByteChannel(socket)
    private val writeMutex = Mutex()

    override val isOpen: Boolean
        get() = socket.isOpen

    override suspend fun readMessage(): JsonObject = codec.readFrame(channel)

    override suspend fun writeMessage(message: JsonObject) {
        writeMutex.withLock {
            codec.writeFrame(channel, message)
        }
    }

    override fun close() {
        runCatching { socket.close() }
    }

    companion object {
        suspend fun connect(
            endpoint: Path,
            codec: LengthPrefixedJsonCodec = LengthPrefixedJsonCodec(),
            security: UnixEndpointSecurity = UnixEndpointSecurity(),
        ): UnixIpcConnection {
            val socket = withContext(Dispatchers.IO) {
                SocketChannel.open(StandardProtocolFamily.UNIX).apply {
                    connect(UnixDomainSocketAddress.of(endpoint))
                }
            }
            try {
                security.verifyPeer(socket)
                return UnixIpcConnection(socket, codec)
            } catch (exception: Throwable) {
                socket.close()
                throw exception
            }
        }
    }
}

private class SocketSuspendByteChannel(
    private val socket: SocketChannel,
) : SuspendByteChannel {
    override suspend fun read(destination: ByteBuffer): Int =
        runInterruptible(Dispatchers.IO) {
            socket.read(destination)
        }

    override suspend fun write(source: ByteBuffer): Int =
        runInterruptible(Dispatchers.IO) {
            socket.write(source)
        }
}
