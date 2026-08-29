package com.github.unscientificjszhai.idecontextforcodex.ipc.transport.windows

import java.io.IOException
import java.nio.ByteBuffer

/**
 * 可由纯 JVM 单元测试替换的 Windows named-pipe native 边界。
 *
 * 生产实现负责 overlapped operation、deadline、取消、SID/DACL 和 native handle 收尾；客户端
 * 连接固定使用 identification-level SQOS，且不向上层暴露可提升冒充级别的配置。transport
 * 只管理 Router 连接生命周期，并在连接成功后继续要求双方执行 PID/SID 校验。
 */
interface WindowsNativePipeAdapter : AutoCloseable {
    fun createServer(
        firstInstance: Boolean,
        accessPolicy: WindowsPipeAccessPolicy = WindowsPipeAccessPolicy.CURRENT_USER_ONLY,
    ): WindowsServerCreateResult

    suspend fun awaitClient(handle: WindowsPipeHandle, timeoutMs: Long)

    suspend fun openClient(timeoutMs: Long): WindowsClientOpenResult

    fun verifyPeer(handle: WindowsPipeHandle, peer: WindowsPipePeer)

    suspend fun read(handle: WindowsPipeHandle, destination: ByteBuffer, timeoutMs: Long): Int

    suspend fun write(handle: WindowsPipeHandle, source: ByteBuffer, timeoutMs: Long): Int

    fun disconnectServer(handle: WindowsPipeHandle)
}

interface WindowsPipeHandle : AutoCloseable {
    val isOpen: Boolean
}

sealed interface WindowsServerCreateResult {
    data class Created(val handle: WindowsPipeHandle) : WindowsServerCreateResult

    data object Conflict : WindowsServerCreateResult
}

sealed interface WindowsClientOpenResult {
    data class Connected(val handle: WindowsPipeHandle) : WindowsClientOpenResult

    data object Absent : WindowsClientOpenResult

    data object Busy : WindowsClientOpenResult
}

enum class WindowsPipePeer {
    SERVER,
    CLIENT,
}

enum class WindowsPipeAccessPolicy {
    CURRENT_USER_ONLY,
}

class WindowsPipeTimeoutException(message: String) : IOException(message)
