package com.github.unscientificjszhai.idecontextforcodex.ipc.transport.windows

import com.github.unscientificjszhai.idecontextforcodex.ipc.protocol.IpcConstants
import com.github.unscientificjszhai.idecontextforcodex.ipc.protocol.LengthPrefixedJsonCodec
import com.github.unscientificjszhai.idecontextforcodex.ipc.protocol.SuspendByteChannel
import com.github.unscientificjszhai.idecontextforcodex.ipc.transport.IpcConnection
import com.google.gson.JsonObject
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

class WindowsNamedPipeConnection internal constructor(
    private val handle: WindowsPipeHandle,
    private val adapter: WindowsNativePipeAdapter,
    private val serverSide: Boolean,
    private val codec: LengthPrefixedJsonCodec = LengthPrefixedJsonCodec(),
    private val readTimeoutMs: Long = INFINITE_TIMEOUT_MS,
    private val writeTimeoutMs: Long = IpcConstants.REQUEST_TIMEOUT_MS,
    private val onClose: () -> Unit = {},
) : IpcConnection {
    private val channel = WindowsPipeByteChannel(
        handle = handle,
        adapter = adapter,
        readTimeoutMs = readTimeoutMs,
        writeTimeoutMs = writeTimeoutMs,
    )
    private val writeMutex = Mutex()
    private val closed = AtomicBoolean(false)

    override val isOpen: Boolean
        get() = !closed.get() && handle.isOpen

    override suspend fun readMessage(): JsonObject = codec.readFrame(channel)

    override suspend fun writeMessage(message: JsonObject) {
        writeMutex.withLock {
            codec.writeFrame(channel, message)
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        if (serverSide) {
            runCatching { adapter.disconnectServer(handle) }
        }
        runCatching { handle.close() }
        onClose()
    }

    companion object {
        const val INFINITE_TIMEOUT_MS = Long.MAX_VALUE
    }
}

private class WindowsPipeByteChannel(
    private val handle: WindowsPipeHandle,
    private val adapter: WindowsNativePipeAdapter,
    private val readTimeoutMs: Long,
    private val writeTimeoutMs: Long,
) : SuspendByteChannel {
    override suspend fun read(destination: ByteBuffer): Int =
        adapter.read(handle, destination, readTimeoutMs)

    override suspend fun write(source: ByteBuffer): Int =
        adapter.write(handle, source, writeTimeoutMs)
}
