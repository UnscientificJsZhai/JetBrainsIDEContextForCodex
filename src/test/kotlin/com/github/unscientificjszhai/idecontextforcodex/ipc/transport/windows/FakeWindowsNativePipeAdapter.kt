package com.github.unscientificjszhai.idecontextforcodex.ipc.transport.windows

import com.github.unscientificjszhai.idecontextforcodex.ipc.transport.UnsafeIpcEndpointException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.nio.ByteBuffer
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

internal class FakeWindowsNativePipeAdapter : WindowsNativePipeAdapter {
    private val lock = Any()
    private val listeners = ArrayDeque<FakeWindowsPipeHandle>()
    private val handles = ConcurrentHashMap.newKeySet<FakeWindowsPipeHandle>()
    private var ownerClaimed = false

    val createPolicies = mutableListOf<WindowsPipeAccessPolicy>()
    val firstInstanceAttempts = AtomicInteger()
    val peerVerificationCount = AtomicInteger()
    val cancelledWaits = AtomicInteger()
    val standbyCreateEntered = CountDownLatch(1)
    val releaseStandbyCreate = CountDownLatch(1)

    @Volatile
    var forceBusy = false

    @Volatile
    var forceAbsent = false

    @Volatile
    var forceAccessDenied = false

    @Volatile
    var forceOwnerConflict = false

    @Volatile
    var blockStandbyCreate = false

    @Volatile
    var rejectPeer = false

    @Volatile
    var identityLookupFails = false

    @Volatile
    var partialReadLimit = Int.MAX_VALUE

    @Volatile
    var partialWriteLimit = Int.MAX_VALUE

    val openHandleCount: Int
        get() = handles.count(FakeWindowsPipeHandle::isOpen)

    override fun createServer(
        firstInstance: Boolean,
        accessPolicy: WindowsPipeAccessPolicy,
    ): WindowsServerCreateResult {
        if (!firstInstance && blockStandbyCreate) {
            standbyCreateEntered.countDown()
            releaseStandbyCreate.await()
        }
        return synchronized(lock) {
            createPolicies += accessPolicy
            if (firstInstance) firstInstanceAttempts.incrementAndGet()
            if (forceOwnerConflict || firstInstance && ownerClaimed) {
                return@synchronized WindowsServerCreateResult.Conflict
            }
            if (firstInstance) ownerClaimed = true

            val handle = newHandle(serverSide = true)
            listeners += handle
            WindowsServerCreateResult.Created(handle)
        }
    }

    override suspend fun awaitClient(handle: WindowsPipeHandle, timeoutMs: Long) {
        val fake = requireHandle(handle)
        try {
            if (timeoutMs == Long.MAX_VALUE) {
                fake.connected.await()
            } else {
                val connected = withTimeoutOrNull(timeoutMs) {
                    fake.connected.await()
                    true
                } ?: false
                if (!connected) throw WindowsPipeTimeoutException("模拟 ConnectNamedPipe 超时")
            }
        } finally {
            if (!fake.connected.isCompleted) cancelledWaits.incrementAndGet()
        }
    }

    override suspend fun openClient(timeoutMs: Long): WindowsClientOpenResult {
        if (forceAccessDenied) {
            throw UnsafeIpcEndpointException("模拟 access denied")
        }
        if (forceBusy) return WindowsClientOpenResult.Busy
        if (forceAbsent) return WindowsClientOpenResult.Absent

        val server = synchronized(lock) {
            while (listeners.isNotEmpty()) {
                val candidate = listeners.removeFirst()
                if (candidate.isOpen && candidate.peer == null) return@synchronized candidate
            }
            null
        } ?: return WindowsClientOpenResult.Absent

        val client = newHandle(serverSide = false)
        synchronized(lock) {
            server.peer = client
            client.peer = server
            server.outgoing = client.incoming
            client.outgoing = server.incoming
            server.connected.complete(Unit)
        }
        return WindowsClientOpenResult.Connected(client)
    }

    override fun verifyPeer(handle: WindowsPipeHandle, peer: WindowsPipePeer) {
        requireHandle(handle)
        peerVerificationCount.incrementAndGet()
        if (identityLookupFails) {
            throw UnsafeIpcEndpointException("模拟 PID/token 查询失败")
        }
        if (rejectPeer) {
            throw UnsafeIpcEndpointException("模拟 SID 不匹配")
        }
    }

    override suspend fun read(
        handle: WindowsPipeHandle,
        destination: ByteBuffer,
        timeoutMs: Long,
    ): Int {
        val fake = requireHandle(handle)
        if (!destination.hasRemaining()) return 0

        val pending = fake.pendingRead
        val bytes = if (pending != null && pending.hasRemaining()) {
            pending
        } else {
            val received = if (timeoutMs == Long.MAX_VALUE) {
                fake.incoming.receiveCatching().getOrNull()
            } else {
                withTimeout(timeoutMs) {
                    fake.incoming.receiveCatching().getOrNull()
                }
            } ?: return -1
            ByteBuffer.wrap(received).also { fake.pendingRead = it }
        }

        val count = minOf(destination.remaining(), bytes.remaining(), partialReadLimit)
        val chunk = ByteArray(count)
        bytes.get(chunk)
        destination.put(chunk)
        if (!bytes.hasRemaining()) fake.pendingRead = null
        return count
    }

    override suspend fun write(
        handle: WindowsPipeHandle,
        source: ByteBuffer,
        timeoutMs: Long,
    ): Int {
        val fake = requireHandle(handle)
        if (!source.hasRemaining()) return 0
        val count = minOf(source.remaining(), partialWriteLimit)
        val bytes = ByteArray(count)
        source.get(bytes)
        val target = fake.outgoing ?: throw IOException("模拟 broken pipe")
        try {
            if (timeoutMs == Long.MAX_VALUE) {
                target.send(bytes)
            } else {
                withTimeout(timeoutMs) {
                    target.send(bytes)
                }
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Throwable) {
            throw IOException("模拟 broken pipe", exception)
        }
        return count
    }

    override fun disconnectServer(handle: WindowsPipeHandle) {
        val fake = handle as? FakeWindowsPipeHandle ?: return
        fake.outgoing?.close()
    }

    override fun close() {
        handles.toList().forEach(FakeWindowsPipeHandle::close)
        handles.clear()
    }

    private fun newHandle(serverSide: Boolean): FakeWindowsPipeHandle {
        lateinit var handle: FakeWindowsPipeHandle
        handle = FakeWindowsPipeHandle(serverSide) {
            handles -= handle
            synchronized(lock) {
                listeners.remove(handle)
                if (handle.serverSide && handles.none { it.serverSide && it.isOpen }) {
                    ownerClaimed = false
                }
            }
        }
        handles += handle
        return handle
    }

    private fun requireHandle(handle: WindowsPipeHandle): FakeWindowsPipeHandle {
        val fake = handle as? FakeWindowsPipeHandle
            ?: throw IOException("handle 不属于 fake adapter")
        if (!fake.isOpen) throw IOException("fake handle 已关闭")
        return fake
    }
}

private class FakeWindowsPipeHandle(
    val serverSide: Boolean,
    private val onClosed: () -> Unit,
) : WindowsPipeHandle {
    private val closed = AtomicBoolean(false)
    val connected = CompletableDeferred<Unit>()
    val incoming = Channel<ByteArray>(Channel.UNLIMITED)

    @Volatile
    var outgoing: Channel<ByteArray>? = null

    @Volatile
    var peer: FakeWindowsPipeHandle? = null

    @Volatile
    var pendingRead: ByteBuffer? = null

    override val isOpen: Boolean
        get() = !closed.get()

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        incoming.close()
        outgoing?.close()
        if (!connected.isCompleted) {
            connected.completeExceptionally(IOException("fake handle 已关闭"))
        }
        onClosed()
    }
}
