package com.github.unscientificjszhai.idecontextforcodex.ipc.transport.windows

import com.github.unscientificjszhai.idecontextforcodex.ipc.transport.UnsafeIpcEndpointException
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.platform.win32.*
import com.sun.jna.platform.win32.WinDef.ULONGByReference
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions
import kotlinx.coroutines.*
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Windows named-pipe 的唯一 JNA 实现。
 *
 * 客户端固定以 identification-level SQOS 打开 pipe，防止 Router 冒充 JetBrains
 * 进程令牌；服务端固定使用当前用户 DACL。连接成功后双方仍须分别校验对端 PID/SID，
 * SQOS 不能替代身份校验。
 *
 * 每个 overlapped operation 只有 waiter 可以 drain、关闭 event 和完成 continuation；
 * cancellation callback 只置位并调用 CancelIoEx。
 */
class JnaWindowsNativePipeAdapter internal constructor(
    private val pipeName: String = WINDOWS_PIPE_NAME,
    private val kernel32: Kernel32 = Kernel32.INSTANCE,
    private val advapi32: Advapi32 = Advapi32.INSTANCE,
    private val extendedKernel32: ExtendedKernel32 = ExtendedKernel32.INSTANCE,
    private val extendedAdvapi32: ExtendedAdvapi32 = ExtendedAdvapi32.INSTANCE,
    waiterDispatcher: ExecutorCoroutineDispatcher? = null,
    currentSidBytesOverride: ByteArray? = null,
    currentSidTextOverride: String? = null,
) : WindowsNativePipeAdapter {
    private val currentSid = if (
        currentSidBytesOverride != null && currentSidTextOverride != null
    ) {
        ProcessSid(currentSidBytesOverride.copyOf(), currentSidTextOverride)
    } else {
        readProcessSid(kernel32.GetCurrentProcess())
    }
    private val ioDispatcher: ExecutorCoroutineDispatcher = waiterDispatcher
        ?: Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "codex-windows-pipe-io").apply { isDaemon = true }
        }.asCoroutineDispatcher()
    private val lifecycleLock = Any()
    private val waiterScope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val handles = ConcurrentHashMap.newKeySet<JnaWindowsPipeHandle>()
    private val operations = ConcurrentHashMap.newKeySet<PendingOperation>()
    private val closed = AtomicBoolean(false)
    private val dispatcherClosed = AtomicBoolean(false)

    override fun createServer(
        firstInstance: Boolean,
        accessPolicy: WindowsPipeAccessPolicy,
    ): WindowsServerCreateResult {
        checkOpen()
        require(accessPolicy == WindowsPipeAccessPolicy.CURRENT_USER_ONLY) {
            "Windows named-pipe 只支持当前用户 DACL"
        }
        val descriptor = createPrivateSecurityDescriptor()
        try {
            val attributes = WinBase.SECURITY_ATTRIBUTES().apply {
                dwLength = WinDef.DWORD(size().toLong())
                lpSecurityDescriptor = descriptor
                bInheritHandle = false
                write()
            }
            val openMode = PIPE_ACCESS_DUPLEX or WinNT.FILE_FLAG_OVERLAPPED or
                    if (firstInstance) FILE_FLAG_FIRST_PIPE_INSTANCE else 0
            val handle = kernel32.CreateNamedPipe(
                pipeName,
                openMode,
                PIPE_TYPE_BYTE or PIPE_READMODE_BYTE or PIPE_WAIT or
                        PIPE_REJECT_REMOTE_CLIENTS,
                MAX_NATIVE_INSTANCES,
                PIPE_BUFFER_BYTES,
                PIPE_BUFFER_BYTES,
                DEFAULT_PIPE_TIMEOUT_MS,
                attributes,
            )
            if (isInvalid(handle)) {
                return when (val error = kernel32.GetLastError()) {
                    WinError.ERROR_ACCESS_DENIED,
                    WinError.ERROR_PIPE_BUSY,
                        -> WindowsServerCreateResult.Conflict

                    else -> throw WindowsNativeIOException("CreateNamedPipeW", error)
                }
            }
            return WindowsServerCreateResult.Created(track(handle))
        } finally {
            kernel32.LocalFree(descriptor)
        }
    }

    override suspend fun awaitClient(handle: WindowsPipeHandle, timeoutMs: Long) {
        val nativeHandle = requireHandle(handle)
        performOverlapped(nativeHandle, timeoutMs, "ConnectNamedPipe") { overlapped ->
            if (kernel32.ConnectNamedPipe(nativeHandle.value, overlapped)) {
                StartResult.ReadyToDrain
            } else {
                when (val error = kernel32.GetLastError()) {
                    WinError.ERROR_IO_PENDING -> StartResult.Pending
                    WinError.ERROR_PIPE_CONNECTED -> StartResult.Completed(0)
                    else -> StartResult.Failed(error)
                }
            }
        }
    }

    override suspend fun openClient(timeoutMs: Long): WindowsClientOpenResult =
        withContext(ioDispatcher) {
            checkOpen()
            val deadlineNanos = if (timeoutMs == Long.MAX_VALUE) {
                Long.MAX_VALUE
            } else {
                System.nanoTime() + timeoutMs.coerceAtLeast(1) * NANOS_PER_MILLISECOND
            }

            while (true) {
                currentCoroutineContext().ensureActive()
                checkOpen()
                val handle = kernel32.CreateFile(
                    pipeName,
                    WinNT.GENERIC_READ or WinNT.GENERIC_WRITE,
                    0,
                    null,
                    WinNT.OPEN_EXISTING,
                    CLIENT_OPEN_FLAGS,
                    null,
                )
                if (!isInvalid(handle)) {
                    return@withContext WindowsClientOpenResult.Connected(track(handle))
                }

                when (val error = kernel32.GetLastError()) {
                    WinError.ERROR_FILE_NOT_FOUND ->
                        return@withContext WindowsClientOpenResult.Absent

                    WinError.ERROR_PIPE_BUSY -> {
                        val remainingMs = remainingMillis(deadlineNanos)
                        if (remainingMs <= 0) {
                            return@withContext WindowsClientOpenResult.Busy
                        }
                        val waitSliceMs = minOf(remainingMs, WAIT_SLICE_MS)
                        if (!kernel32.WaitNamedPipe(pipeName, waitSliceMs)) {
                            when (kernel32.GetLastError()) {
                                WinError.ERROR_FILE_NOT_FOUND ->
                                    return@withContext WindowsClientOpenResult.Absent

                                WinError.ERROR_ACCESS_DENIED ->
                                    throw UnsafeIpcEndpointException(
                                        "Windows named-pipe endpoint 拒绝访问",
                                    )

                                WinError.ERROR_SEM_TIMEOUT -> {
                                    if (remainingMillis(deadlineNanos) <= 0) {
                                        return@withContext WindowsClientOpenResult.Busy
                                    }
                                    continue
                                }

                                else -> return@withContext WindowsClientOpenResult.Busy
                            }
                        }
                    }

                    WinError.ERROR_ACCESS_DENIED ->
                        throw UnsafeIpcEndpointException("Windows named-pipe endpoint 拒绝访问")

                    else -> throw WindowsNativeIOException("CreateFileW", error)
                }
            }
            @Suppress("UNREACHABLE_CODE")
            WindowsClientOpenResult.Absent
        }

    override fun verifyPeer(handle: WindowsPipeHandle, peer: WindowsPipePeer) {
        val nativeHandle = requireHandle(handle)
        val processId = ULONGByReference()
        val resolved = when (peer) {
            WindowsPipePeer.SERVER ->
                kernel32.GetNamedPipeServerProcessId(nativeHandle.value, processId)

            WindowsPipePeer.CLIENT ->
                kernel32.GetNamedPipeClientProcessId(nativeHandle.value, processId)
        }
        if (!resolved) {
            throw UnsafeIpcEndpointException("无法解析 Windows named-pipe 对端进程")
        }

        val process = kernel32.OpenProcess(
            WinNT.PROCESS_QUERY_LIMITED_INFORMATION,
            false,
            processId.value.toInt(),
        )
        if (isInvalid(process)) {
            throw UnsafeIpcEndpointException("无法打开 Windows named-pipe 对端进程")
        }
        try {
            val peerSid = readProcessSid(process)
            val sameUser = advapi32.EqualSid(
                WinNT.PSID(currentSid.bytes),
                WinNT.PSID(peerSid.bytes),
            )
            if (!sameUser) {
                throw UnsafeIpcEndpointException("Windows named-pipe 对端用户不匹配")
            }
        } finally {
            kernel32.CloseHandle(process)
        }
    }

    override suspend fun read(
        handle: WindowsPipeHandle,
        destination: ByteBuffer,
        timeoutMs: Long,
    ): Int {
        if (!destination.hasRemaining()) return 0
        val nativeHandle = requireHandle(handle)
        val capacity = minOf(destination.remaining(), PIPE_IO_CHUNK_BYTES)
        val buffer = Memory(capacity.toLong())
        val result = try {
            performOverlapped(
                handle = nativeHandle,
                timeoutMs = timeoutMs,
                operationName = "ReadFile",
                buffer = buffer,
                captureBuffer = true,
            ) { overlapped ->
                if (extendedKernel32.ReadFile(
                        nativeHandle.value,
                        buffer,
                        capacity,
                        null,
                        overlapped,
                    )
                ) {
                    StartResult.ReadyToDrain
                } else {
                    when (val error = kernel32.GetLastError()) {
                        WinError.ERROR_IO_PENDING -> StartResult.Pending
                        else -> StartResult.Failed(error)
                    }
                }
            }
        } catch (exception: WindowsNativeIOException) {
            if (
                exception.errorCode == WinError.ERROR_BROKEN_PIPE ||
                exception.errorCode == WinError.ERROR_PIPE_NOT_CONNECTED
            ) {
                return -1
            }
            throw exception
        }
        if (result.bytesTransferred == 0) return -1
        destination.put(checkNotNull(result.capturedBytes))
        return result.bytesTransferred
    }

    override suspend fun write(
        handle: WindowsPipeHandle,
        source: ByteBuffer,
        timeoutMs: Long,
    ): Int {
        if (!source.hasRemaining()) return 0
        val nativeHandle = requireHandle(handle)
        val bytes = ByteArray(minOf(source.remaining(), PIPE_IO_CHUNK_BYTES))
        source.duplicate().get(bytes)
        val buffer = Memory(bytes.size.toLong()).apply {
            write(0, bytes, 0, bytes.size)
        }
        val result = performOverlapped(
            handle = nativeHandle,
            timeoutMs = timeoutMs,
            operationName = "WriteFile",
            buffer = buffer,
        ) { overlapped ->
            if (extendedKernel32.WriteFile(
                    nativeHandle.value,
                    buffer,
                    bytes.size,
                    null,
                    overlapped,
                )
            ) {
                StartResult.ReadyToDrain
            } else {
                when (val error = kernel32.GetLastError()) {
                    WinError.ERROR_IO_PENDING -> StartResult.Pending
                    else -> StartResult.Failed(error)
                }
            }
        }
        source.position(source.position() + result.bytesTransferred)
        return result.bytesTransferred
    }

    override fun disconnectServer(handle: WindowsPipeHandle) {
        val nativeHandle = handle as? JnaWindowsPipeHandle ?: return
        if (nativeHandle.isOpen) {
            kernel32.DisconnectNamedPipe(nativeHandle.value)
        }
    }

    private suspend fun performOverlapped(
        handle: JnaWindowsPipeHandle,
        timeoutMs: Long,
        operationName: String,
        buffer: Memory? = null,
        captureBuffer: Boolean = false,
        start: (WinBase.OVERLAPPED) -> StartResult,
    ): OverlappedResult = suspendCancellableCoroutine { continuation ->
        val event = kernel32.CreateEvent(null, true, false, null)
        if (isInvalid(event)) {
            buffer?.close()
            continuation.resumeWith(Result.failure(WindowsNativeIOException("CreateEventW", kernel32.GetLastError())))
            return@suspendCancellableCoroutine
        }

        val overlapped = WinBase.OVERLAPPED().apply {
            hEvent = event
            write()
        }
        val operation = PendingOperation(
            handle = handle,
            event = event,
            overlapped = overlapped,
            timeoutMs = timeoutMs,
            operationName = operationName,
            continuation = continuation,
            buffer = buffer,
            captureBuffer = captureBuffer,
        )
        val operationRegistered = synchronized(lifecycleLock) {
            if (closed.get() || !handle.isOpen) {
                false
            } else {
                operations += operation
                true
            }
        }
        if (!operationRegistered) {
            buffer?.close()
            kernel32.CloseHandle(event)
            continuation.resumeWith(Result.failure(IOException("Windows native pipe adapter 已关闭")))
            return@suspendCancellableCoroutine
        }
        continuation.invokeOnCancellation {
            operation.cancellationRequested.set(true)
            requestCancellation(operation)
        }

        val startResult = if (
            continuation.isActive &&
            handle.isOpen &&
            !operation.cancellationRequested.get()
        ) {
            try {
                start(overlapped)
            } catch (exception: Throwable) {
                StartResult.StartException(exception)
            }
        } else {
            StartResult.CancelledBeforeStart
        }
        operation.startResult = startResult
        if (operation.cancellationRequested.get() && startResult == StartResult.Pending) {
            requestCancellation(operation)
        }

        waiterScope.launch {
            finishOperation(operation)
        }
    }

    private fun finishOperation(operation: PendingOperation) {
        var result: Result<OverlappedResult>
        try {
            val nativeResult = when (val startResult = operation.startResult) {
                is StartResult.Completed ->
                    applyRequestedOutcome(
                        operation,
                        Result.success(startResult.bytesTransferred),
                    )

                is StartResult.Failed ->
                    Result.failure(WindowsNativeIOException(operation.operationName, startResult.errorCode))

                is StartResult.StartException -> Result.failure(startResult.exception)
                StartResult.CancelledBeforeStart ->
                    Result.failure(CancellationException("Windows named-pipe operation 已取消"))

                StartResult.ReadyToDrain ->
                    applyRequestedOutcome(
                        operation,
                        drainOperation(operation, wait = false),
                    )

                StartResult.Pending -> waitForPending(operation)
            }
            result = nativeResult.mapCatching { transferred ->
                OverlappedResult(
                    bytesTransferred = transferred,
                    capturedBytes = if (operation.captureBuffer) {
                        checkNotNull(operation.buffer)
                            .getByteArray(0, transferred)
                    } else {
                        null
                    },
                )
            }
        } catch (exception: Throwable) {
            result = Result.failure(exception)
        } finally {
            if (operation.finalized.compareAndSet(false, true)) {
                operation.buffer?.close()
                kernel32.CloseHandle(operation.event)
            }
            synchronized(lifecycleLock) {
                operations -= operation
                closeNativeHandleIfIdle(operation.handle)
            }
            closeDispatcherWhenIdle()
        }

        result.fold(
            onSuccess = { value -> operation.continuation.tryResumeSafely(value) },
            onFailure = { exception -> operation.continuation.tryResumeExceptionSafely(exception) },
        )
    }

    private fun waitForPending(operation: PendingOperation): Result<Int> {
        val initialWait = kernel32.WaitForSingleObject(
            operation.event,
            timeoutToNative(operation.timeoutMs),
        )
        if (initialWait == WinBase.WAIT_OBJECT_0) {
            return applyRequestedOutcome(
                operation,
                drainOperation(operation, wait = false),
            )
        }

        val initialWaitFailure = if (initialWait == WinError.WAIT_TIMEOUT) {
            operation.timeoutRequested.set(true)
            null
        } else {
            WindowsNativeIOException("WaitForSingleObject", kernel32.GetLastError())
        }
        requestCancellation(operation)
        val terminalWait = kernel32.WaitForSingleObject(operation.event, WinBase.INFINITE)
        val terminalWaitFailure = if (terminalWait == WinBase.WAIT_OBJECT_0) {
            null
        } else {
            WindowsNativeIOException("WaitForSingleObject", kernel32.GetLastError())
        }
        val drained = drainOperation(
            operation = operation,
            wait = terminalWaitFailure != null,
        )
        val requestedOutcome = applyRequestedOutcome(operation, drained)
        if (operation.cancellationRequested.get() || operation.timeoutRequested.get()) {
            return requestedOutcome
        }
        return initialWaitFailure?.let(Result.Companion::failure)
            ?: terminalWaitFailure?.let(Result.Companion::failure)
            ?: requestedOutcome
    }

    private fun drainOperation(
        operation: PendingOperation,
        wait: Boolean,
    ): Result<Int> {
        val transferred = IntByReference()
        if (extendedKernel32.GetOverlappedResult(
                operation.handle.value,
                operation.overlapped,
                transferred,
                wait,
            )
        ) {
            return Result.success(transferred.value)
        }
        return Result.failure(
            WindowsNativeIOException(operation.operationName, kernel32.GetLastError()),
        )
    }

    private fun applyRequestedOutcome(
        operation: PendingOperation,
        drained: Result<Int>,
    ): Result<Int> = when {
        operation.cancellationRequested.get() ->
            Result.failure(CancellationException("Windows named-pipe operation 已取消"))

        operation.timeoutRequested.get() ->
            Result.failure(WindowsPipeTimeoutException("${operation.operationName} 超时"))

        else -> drained
    }

    private fun requestCancellation(operation: PendingOperation) {
        if (!operation.finalized.get() && operation.handle.isNativeOpen) {
            extendedKernel32.CancelIoEx(operation.handle.value, operation.overlapped)
            // ERROR_NOT_FOUND 表示 I/O 已完成；仍由 waiter drain 和收尾。
        }
    }

    private fun readProcessSid(process: WinNT.HANDLE): ProcessSid {
        val tokenReference = WinNT.HANDLEByReference()
        if (!advapi32.OpenProcessToken(process, WinNT.TOKEN_QUERY, tokenReference)) {
            throw UnsafeIpcEndpointException("无法读取 Windows 进程 token")
        }
        val token = tokenReference.value
        if (isInvalid(token)) {
            throw UnsafeIpcEndpointException("Windows 进程 token handle 无效")
        }
        try {
            val tokenInformationLength = IntByReference()
            val unexpectedlySucceeded = advapi32.GetTokenInformation(
                token,
                WinNT.TOKEN_INFORMATION_CLASS.TokenUser,
                null,
                0,
                tokenInformationLength,
            )
            if (unexpectedlySucceeded || kernel32.GetLastError() != WinError.ERROR_INSUFFICIENT_BUFFER) {
                throw UnsafeIpcEndpointException("无法确定 Windows 进程 SID 缓冲区")
            }

            val tokenUser = WinNT.TOKEN_USER(tokenInformationLength.value)
            if (!advapi32.GetTokenInformation(
                    token,
                    WinNT.TOKEN_INFORMATION_CLASS.TokenUser,
                    tokenUser,
                    tokenInformationLength.value,
                    tokenInformationLength,
                )
            ) {
                throw UnsafeIpcEndpointException("无法读取 Windows 进程 SID")
            }
            val sid = tokenUser.User.Sid
            return ProcessSid(sid.bytes, sid.sidString)
        } catch (_: Exception) {
            throw UnsafeIpcEndpointException("无法读取 Windows 进程 SID")
        } finally {
            kernel32.CloseHandle(token)
        }
    }

    private fun createPrivateSecurityDescriptor(): Pointer {
        val descriptor = PointerByReference()
        val sddl = "D:P(A;;GA;;;${currentSid.text})"
        if (!extendedAdvapi32.ConvertStringSecurityDescriptorToSecurityDescriptorW(
                WString(sddl),
                SDDL_REVISION_1,
                descriptor,
                null,
            )
        ) {
            throw WindowsNativeIOException(
                "ConvertStringSecurityDescriptorToSecurityDescriptorW",
                kernel32.GetLastError(),
            )
        }
        return descriptor.value
            ?: throw UnsafeIpcEndpointException(
                "Windows named-pipe 安全描述符为空",
            )
    }

    private fun track(handle: WinNT.HANDLE): JnaWindowsPipeHandle {
        lateinit var tracked: JnaWindowsPipeHandle
        tracked = JnaWindowsPipeHandle(handle, kernel32) {
            onHandleCloseRequested(tracked)
        }
        synchronized(lifecycleLock) {
            if (closed.get()) {
                tracked.closeNative()
                throw IOException("Windows native pipe adapter 已关闭")
            }
            handles += tracked
        }
        return tracked
    }

    private fun requireHandle(handle: WindowsPipeHandle): JnaWindowsPipeHandle {
        val nativeHandle = handle as? JnaWindowsPipeHandle
            ?: throw IOException("Windows pipe handle 不属于 JNA adapter")
        if (!nativeHandle.isOpen) throw IOException("Windows pipe handle 已关闭")
        return nativeHandle
    }

    private fun onHandleCloseRequested(handle: JnaWindowsPipeHandle) {
        val pendingOperations = synchronized(lifecycleLock) {
            val pending = operations.filter { it.handle === handle }
            closeNativeHandleIfIdle(handle)
            pending
        }
        pendingOperations.forEach { operation ->
            operation.cancellationRequested.set(true)
            requestCancellation(operation)
        }
    }

    private fun closeNativeHandleIfIdle(handle: JnaWindowsPipeHandle) {
        if (
            !handle.isOpen &&
            operations.none { it.handle === handle } &&
            handle.closeNative()
        ) {
            handles -= handle
        }
    }

    private fun checkOpen() {
        if (closed.get()) throw IOException("Windows native pipe adapter 已关闭")
    }

    override fun close() {
        val activeHandles = synchronized(lifecycleLock) {
            if (!closed.compareAndSet(false, true)) return
            handles.toList()
        }
        operations.toList().forEach { operation ->
            operation.cancellationRequested.set(true)
            requestCancellation(operation)
        }
        activeHandles.forEach(JnaWindowsPipeHandle::close)
        closeDispatcherWhenIdle()
    }

    private fun closeDispatcherWhenIdle() {
        if (closed.get() && operations.isEmpty() && dispatcherClosed.compareAndSet(false, true)) {
            waiterScope.cancel()
            ioDispatcher.close()
        }
    }

    private fun remainingMillis(deadlineNanos: Long): Int {
        if (deadlineNanos == Long.MAX_VALUE) return WinBase.INFINITE
        val remaining = (deadlineNanos - System.nanoTime()) / NANOS_PER_MILLISECOND
        return remaining.coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
    }

    private fun timeoutToNative(timeoutMs: Long): Int =
        if (timeoutMs == Long.MAX_VALUE) {
            WinBase.INFINITE
        } else {
            timeoutMs.coerceIn(1, Int.MAX_VALUE.toLong()).toInt()
        }

    private fun isInvalid(handle: WinNT.HANDLE?): Boolean =
        handle == null || handle == WinBase.INVALID_HANDLE_VALUE ||
                handle.pointer == null || Pointer.nativeValue(handle.pointer) == 0L

    private data class ProcessSid(val bytes: ByteArray, val text: String)

    private data class OverlappedResult(
        val bytesTransferred: Int,
        val capturedBytes: ByteArray?,
    )

    private sealed interface StartResult {
        data class Completed(val bytesTransferred: Int) : StartResult
        data class Failed(val errorCode: Int) : StartResult
        data class StartException(val exception: Throwable) : StartResult
        data object ReadyToDrain : StartResult
        data object Pending : StartResult
        data object CancelledBeforeStart : StartResult
    }

    private class PendingOperation(
        val handle: JnaWindowsPipeHandle,
        val event: WinNT.HANDLE,
        val overlapped: WinBase.OVERLAPPED,
        val timeoutMs: Long,
        val operationName: String,
        val continuation: CancellableContinuation<OverlappedResult>,
        val buffer: Memory?,
        val captureBuffer: Boolean,
    ) {
        val cancellationRequested = AtomicBoolean(false)
        val timeoutRequested = AtomicBoolean(false)
        val finalized = AtomicBoolean(false)

        @Volatile
        var startResult: StartResult = StartResult.CancelledBeforeStart
    }

    companion object {
        const val WINDOWS_PIPE_NAME = """\\.\pipe\codex-ipc"""
        private const val PIPE_ACCESS_DUPLEX = 0x00000003
        private const val FILE_FLAG_FIRST_PIPE_INSTANCE = 0x00080000
        private const val SECURITY_IDENTIFICATION = 0x00010000
        private const val SECURITY_SQOS_PRESENT = 0x00100000
        private const val CLIENT_OPEN_FLAGS =
            WinNT.FILE_FLAG_OVERLAPPED or SECURITY_SQOS_PRESENT or SECURITY_IDENTIFICATION
        private const val PIPE_TYPE_BYTE = 0x00000000
        private const val PIPE_READMODE_BYTE = 0x00000000
        private const val PIPE_WAIT = 0x00000000
        private const val PIPE_REJECT_REMOTE_CLIENTS = 0x00000008
        private const val MAX_NATIVE_INSTANCES = 18
        private const val PIPE_BUFFER_BYTES = 64 * 1024
        private const val PIPE_IO_CHUNK_BYTES = 64 * 1024
        private const val DEFAULT_PIPE_TIMEOUT_MS = 4_000
        private const val SDDL_REVISION_1 = 1
        private const val NANOS_PER_MILLISECOND = 1_000_000L
        private const val WAIT_SLICE_MS = 100
    }
}

private class JnaWindowsPipeHandle(
    val value: WinNT.HANDLE,
    private val kernel32: Kernel32,
    private val onCloseRequested: () -> Unit,
) : WindowsPipeHandle {
    private val closed = AtomicBoolean(false)
    private val nativeClosed = AtomicBoolean(false)

    override val isOpen: Boolean
        get() = !closed.get()

    val isNativeOpen: Boolean
        get() = !nativeClosed.get()

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        onCloseRequested()
    }

    fun closeNative(): Boolean {
        if (!nativeClosed.compareAndSet(false, true)) return false
        kernel32.CloseHandle(value)
        return true
    }
}

private class WindowsNativeIOException(
    operation: String,
    val errorCode: Int,
) : IOException("$operation 失败（Windows 错误码 $errorCode）")

private fun <T> CancellableContinuation<T>.tryResumeSafely(value: T) {
    if (!isActive) return
    runCatching { resume(value) }
}

private fun <T> CancellableContinuation<T>.tryResumeExceptionSafely(exception: Throwable) {
    if (!isActive) return
    runCatching { resumeWithException(exception) }
}

internal interface ExtendedKernel32 : StdCallLibrary {
    fun ReadFile(
        file: WinNT.HANDLE,
        buffer: Pointer,
        bytesToRead: Int,
        bytesRead: IntByReference?,
        overlapped: WinBase.OVERLAPPED,
    ): Boolean

    fun WriteFile(
        file: WinNT.HANDLE,
        buffer: Pointer,
        bytesToWrite: Int,
        bytesWritten: IntByReference?,
        overlapped: WinBase.OVERLAPPED,
    ): Boolean

    fun CancelIoEx(
        file: WinNT.HANDLE,
        overlapped: WinBase.OVERLAPPED?,
    ): Boolean

    fun GetOverlappedResult(
        file: WinNT.HANDLE,
        overlapped: WinBase.OVERLAPPED,
        bytesTransferred: IntByReference,
        wait: Boolean,
    ): Boolean

    companion object {
        val INSTANCE: ExtendedKernel32 = Native.load(
            "kernel32",
            ExtendedKernel32::class.java,
            W32APIOptions.UNICODE_OPTIONS,
        )
    }
}

internal interface ExtendedAdvapi32 : StdCallLibrary {
    fun ConvertStringSecurityDescriptorToSecurityDescriptorW(
        stringSecurityDescriptor: WString,
        stringSDRevision: Int,
        securityDescriptor: PointerByReference,
        securityDescriptorSize: IntByReference?,
    ): Boolean

    companion object {
        val INSTANCE: ExtendedAdvapi32 = Native.load(
            "advapi32",
            ExtendedAdvapi32::class.java,
            W32APIOptions.UNICODE_OPTIONS,
        )
    }
}
