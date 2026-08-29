package com.github.unscientificjszhai.idecontextforcodex.ipc.transport.windows

import com.github.unscientificjszhai.idecontextforcodex.ipc.transport.UnsafeIpcEndpointException
import com.sun.jna.Memory
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.platform.win32.Advapi32
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.WinBase
import com.sun.jna.platform.win32.WinError
import com.sun.jna.platform.win32.WinNT
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class JnaWindowsNativePipeAdapterTest {
    @Test
    fun `timeout 按 cancel wait drain finalize 顺序收尾`() = runBlocking {
        val native = FakeJnaNative(
            waitResults = ArrayDeque(listOf(WinError.WAIT_TIMEOUT, WinBase.WAIT_OBJECT_0)),
            overlappedError = WinError.ERROR_OPERATION_ABORTED,
        )
        val adapter = native.adapter()
        val handle = connectedHandle(adapter)

        assertThrows(WindowsPipeTimeoutException::class.java) {
            runBlocking {
                adapter.read(handle, ByteBuffer.allocate(8), 25)
            }
        }

        assertOrdered(native.operations, "wait:25", "cancel", "wait:-1", "drain", "close:event")
        assertEquals(1, native.operations.count { it == "close:event" })
        handle.close()
        adapter.close()
    }

    @Test
    fun `coroutine cancel 只请求 CancelIoEx 并由 waiter drain`() = runBlocking {
        val native = FakeJnaNative(
            waitForCancellation = true,
            overlappedError = WinError.ERROR_OPERATION_ABORTED,
        )
        val adapter = native.adapter()
        val handle = connectedHandle(adapter)
        val readJob = launch {
            adapter.read(handle, ByteBuffer.allocate(8), Long.MAX_VALUE)
        }
        withTimeout(2_000) {
            while (native.readStarted.count > 0) delay(10)
        }

        readJob.cancelAndJoin()
        withTimeout(2_000) {
            while ("close:event" !in native.operations) delay(10)
        }

        assertOrdered(native.operations, "cancel", "drain", "close:event")
        assertEquals(1, native.operations.count { it == "close:event" })
        handle.close()
        adapter.close()
    }

    @Test
    fun `同步完成不等待并由 waiter drain 结果`() = runBlocking {
        val native = FakeJnaNative(synchronousReadBytes = byteArrayOf(0x2A))
        val adapter = native.adapter()
        val handle = connectedHandle(adapter)
        val destination = ByteBuffer.allocate(8)

        val count = adapter.read(handle, destination, 100)

        assertEquals(1, count)
        assertEquals(0x2A, destination.array()[0].toInt())
        assertFalse(native.operations.any { it.startsWith("wait:") })
        assertTrue("drain" in native.operations)
        assertEquals(1, native.operations.count { it == "close:event" })
        handle.close()
        adapter.close()
    }

    @Test
    fun `adapter close 会先取消并 drain 再释放 native handles`() = runBlocking {
        val native = FakeJnaNative(
            waitForCancellation = true,
            overlappedError = WinError.ERROR_OPERATION_ABORTED,
        )
        val adapter = native.adapter()
        val handle = connectedHandle(adapter)
        val readJob = launch {
            adapter.read(handle, ByteBuffer.allocate(8), Long.MAX_VALUE)
        }
        withTimeout(2_000) {
            while (native.readStarted.count > 0) delay(10)
        }

        adapter.close()
        withTimeout(2_000) {
            readJob.join()
            while ("close:pipe" !in native.operations) delay(10)
        }
        adapter.close()

        assertOrdered(
            native.operations,
            "cancel",
            "drain",
            "close:event",
            "close:pipe",
        )
        assertEquals(1, native.operations.count { it == "close:event" })
        assertEquals(1, native.operations.count { it == "close:pipe" })
        assertFalse(handle.isOpen)
    }

    @Test
    fun `server DACL 只授予当前用户 SID 完全访问`() {
        val native = FakeJnaNative()
        val adapter = native.adapter()

        val result = adapter.createServer(firstInstance = true)

        assertTrue(result is WindowsServerCreateResult.Created)
        assertEquals("D:P(A;;GA;;;S-1-5-21-1000)", native.securityDescriptor)
        assertTrue(native.pipeMode and 0x00000008 != 0)
        (result as WindowsServerCreateResult.Created).handle.close()
        adapter.close()
    }

    @Test
    fun `CreateFile client 始终使用 identification level SQOS`() = runBlocking {
        val native = FakeJnaNative(
            createFileErrors = ArrayDeque(listOf(WinError.ERROR_PIPE_BUSY)),
            waitNamedPipeSucceeds = true,
        )
        val adapter = native.adapter()

        val result = adapter.openClient(100)

        assertTrue(result is WindowsClientOpenResult.Connected)
        assertEquals(2, native.createFileFlags.size)
        native.createFileFlags.forEach { flags ->
            assertEquals(0x40110000, flags)
            assertTrue(flags and 0x40000000 != 0)
            assertTrue(flags and 0x00100000 != 0)
            assertEquals(0x00010000, flags and 0x00030000)
        }
        (result as WindowsClientOpenResult.Connected).handle.close()
        adapter.close()
    }

    @Test
    fun `Windows native 接受 identification level SQOS 并完成双方校验`() {
        assumeTrue(
            "仅在 Windows 上执行真实 named-pipe 冒烟测试",
            System.getProperty("os.name").startsWith("Windows", ignoreCase = true),
        )

        runBlocking {
            withTimeout(2_000) {
                val pipeName = """\\.\pipe\codex-ipc-sqos-${UUID.randomUUID()}"""
                val adapter = JnaWindowsNativePipeAdapter(pipeName = pipeName)
                try {
                    val serverResult = adapter.createServer(firstInstance = true)
                    val serverHandle = (serverResult as? WindowsServerCreateResult.Created)?.handle
                        ?: error("无法创建独立的 Windows named-pipe server")
                    try {
                        val acceptJob = async {
                            adapter.awaitClient(serverHandle, 1_500)
                            adapter.verifyPeer(serverHandle, WindowsPipePeer.CLIENT)
                        }
                        val clientResult = adapter.openClient(1_500)
                        val clientHandle = (clientResult as? WindowsClientOpenResult.Connected)?.handle
                            ?: error("无法连接独立的 Windows named-pipe server")
                        try {
                            adapter.verifyPeer(clientHandle, WindowsPipePeer.SERVER)
                            acceptJob.await()
                        } finally {
                            clientHandle.close()
                        }
                    } finally {
                        adapter.disconnectServer(serverHandle)
                        serverHandle.close()
                    }
                } finally {
                    adapter.close()
                }
            }
        }
    }

    @Test
    fun `CreateFile access denied 映射为安全拒绝`() {
        val native = FakeJnaNative(
            createFileErrors = ArrayDeque(listOf(WinError.ERROR_ACCESS_DENIED)),
        )
        val adapter = native.adapter()

        assertThrows(UnsafeIpcEndpointException::class.java) {
            runBlocking { adapter.openClient(100) }
        }

        adapter.close()
    }

    @Test
    fun `同步 WriteFile 使用稳定 native buffer 并推进 source`() = runBlocking {
        val native = FakeJnaNative(synchronousWrite = true)
        val adapter = native.adapter()
        val handle = connectedHandle(adapter)
        val source = ByteBuffer.wrap(byteArrayOf(1, 2, 3))

        val count = adapter.write(handle, source, 100)

        assertEquals(3, count)
        assertEquals(3, source.position())
        assertTrue(byteArrayOf(1, 2, 3).contentEquals(native.writtenBytes))
        assertEquals(1, native.operations.count { it == "close:event" })
        handle.close()
        adapter.close()
    }

    @Test
    fun `ConnectNamedPipe timeout 同样先 cancel 再 drain`() = runBlocking {
        val native = FakeJnaNative(
            waitResults = ArrayDeque(listOf(WinError.WAIT_TIMEOUT, WinBase.WAIT_OBJECT_0)),
            overlappedError = WinError.ERROR_OPERATION_ABORTED,
        )
        val adapter = native.adapter()
        val handle = (
            adapter.createServer(firstInstance = true) as
                WindowsServerCreateResult.Created
            ).handle

        assertThrows(WindowsPipeTimeoutException::class.java) {
            runBlocking { adapter.awaitClient(handle, 25) }
        }

        assertOrdered(native.operations, "wait:25", "cancel", "wait:-1", "drain", "close:event")
        handle.close()
        adapter.close()
    }

    @Test
    fun `cancel 与 native completion 竞争仍只 finalize 一次`() = runBlocking {
        val native = FakeJnaNative(
            waitForCancellation = true,
            overlappedError = 0,
        )
        val adapter = native.adapter()
        val handle = connectedHandle(adapter)
        val readJob = launch {
            adapter.read(handle, ByteBuffer.allocate(8), Long.MAX_VALUE)
        }
        withTimeout(2_000) {
            while (native.readStarted.count > 0) delay(10)
        }

        readJob.cancelAndJoin()
        withTimeout(2_000) {
            while ("close:event" !in native.operations) delay(10)
        }

        assertOrdered(native.operations, "cancel", "drain", "close:event")
        assertEquals(1, native.operations.count { it == "close:event" })
        handle.close()
        adapter.close()
    }

    @Test
    fun `wait 失败也会 cancel 并等待终态后 drain`() = runBlocking {
        val native = FakeJnaNative(
            waitResults = ArrayDeque(listOf(WinBase.WAIT_FAILED, WinBase.WAIT_OBJECT_0)),
            overlappedError = WinError.ERROR_OPERATION_ABORTED,
        )
        val adapter = native.adapter()
        val handle = connectedHandle(adapter)

        assertThrows(IOException::class.java) {
            runBlocking {
                adapter.read(handle, ByteBuffer.allocate(8), 100)
            }
        }

        assertOrdered(native.operations, "wait:100", "cancel", "wait:-1", "drain", "close:event")
        handle.close()
        adapter.close()
    }

    private suspend fun connectedHandle(
        adapter: JnaWindowsNativePipeAdapter,
    ): WindowsPipeHandle =
        (adapter.openClient(100) as WindowsClientOpenResult.Connected).handle

    private fun assertOrdered(actual: List<String>, vararg expected: String) {
        var previousIndex = -1
        expected.forEach { item ->
            val index = actual.indexOf(item)
            assertTrue("$item 未出现在 $actual", index >= 0)
            assertTrue("$item 顺序错误：$actual", index > previousIndex)
            previousIndex = index
        }
    }
}

private class FakeJnaNative(
    private val waitResults: ArrayDeque<Int> = ArrayDeque(),
    private val waitForCancellation: Boolean = false,
    private val overlappedError: Int = 0,
    private val synchronousReadBytes: ByteArray? = null,
    private val createFileErrors: ArrayDeque<Int> = ArrayDeque(),
    private val waitNamedPipeSucceeds: Boolean = false,
    private val synchronousWrite: Boolean = false,
) {
    val operations: MutableList<String> = Collections.synchronizedList(mutableListOf())
    val createFileFlags: MutableList<Int> = Collections.synchronizedList(mutableListOf())
    val readStarted = CountDownLatch(1)
    var securityDescriptor: String? = null
    var writtenBytes: ByteArray? = null
    var pipeMode: Int = 0

    private val lastError = AtomicInteger()
    private val transferredBytes = AtomicInteger(1)
    private val cancelSignal = CountDownLatch(1)
    private val pipeHandle = WinNT.HANDLE(Pointer(1))
    private val eventHandle = WinNT.HANDLE(Pointer(2))
    private val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private var pendingBuffer: Memory? = null

    fun adapter(): JnaWindowsNativePipeAdapter =
        JnaWindowsNativePipeAdapter(
            kernel32 = proxy(Kernel32::class.java, ::invokeKernel32),
            advapi32 = proxy(Advapi32::class.java, ::defaultInvocation),
            extendedKernel32 = proxy(ExtendedKernel32::class.java, ::invokeExtendedKernel32),
            extendedAdvapi32 = proxy(ExtendedAdvapi32::class.java, ::invokeExtendedAdvapi32),
            waiterDispatcher = dispatcher,
            currentSidBytesOverride = byteArrayOf(1, 2, 3, 4),
            currentSidTextOverride = "S-1-5-21-1000",
        )

    private fun invokeKernel32(method: java.lang.reflect.Method, arguments: Array<out Any?>): Any? =
        when (method.name) {
            "CreateFile" -> {
                createFileFlags += arguments[5] as Int
                if (createFileErrors.isEmpty()) {
                    pipeHandle
                } else {
                    lastError.set(createFileErrors.removeFirst())
                    WinBase.INVALID_HANDLE_VALUE
                }
            }

            "CreateNamedPipe" -> {
                pipeMode = arguments[2] as Int
                pipeHandle
            }
            "ConnectNamedPipe" -> {
                lastError.set(WinError.ERROR_IO_PENDING)
                false
            }

            "CreateEvent" -> eventHandle
            "GetLastError" -> lastError.get()
            "WaitNamedPipe" -> waitNamedPipeSucceeds
            "WaitForSingleObject" -> {
                val timeout = arguments[1] as Int
                operations += "wait:$timeout"
                if (waitForCancellation) {
                    cancelSignal.await(2, TimeUnit.SECONDS)
                    WinBase.WAIT_OBJECT_0
                } else {
                    waitResults.removeFirst().also { result ->
                        if (result == WinBase.WAIT_FAILED) {
                            lastError.set(WinError.ERROR_INVALID_HANDLE)
                        }
                    }
                }
            }

            "CloseHandle" -> {
                val handle = arguments[0] as WinNT.HANDLE
                operations += if (handle.pointer == eventHandle.pointer) "close:event" else "close:pipe"
                true
            }

            else -> defaultReturn(method.returnType)
        }

    private fun invokeExtendedKernel32(
        method: java.lang.reflect.Method,
        arguments: Array<out Any?>,
    ): Any? = when (method.name) {
        "ReadFile" -> {
            readStarted.countDown()
            pendingBuffer = arguments[1] as Memory
            val synchronous = synchronousReadBytes
            if (synchronous == null) {
                lastError.set(WinError.ERROR_IO_PENDING)
                false
            } else {
                val target = arguments[1] as Pointer
                target.write(0, synchronous, 0, synchronous.size)
                transferredBytes.set(synchronous.size)
                true
            }
        }

        "WriteFile" -> {
            val buffer = arguments[1] as Memory
            pendingBuffer = buffer
            if (synchronousWrite) {
                val count = arguments[2] as Int
                writtenBytes = buffer.getByteArray(0, count)
                transferredBytes.set(count)
                true
            } else {
                lastError.set(WinError.ERROR_IO_PENDING)
                false
            }
        }

        "CancelIoEx" -> {
            operations += "cancel"
            cancelSignal.countDown()
            true
        }

        "GetOverlappedResult" -> {
            operations += "drain"
            check(pendingBuffer?.valid() != false) {
                "overlapped drain 前 native buffer 已释放"
            }
            if (overlappedError == 0) {
                (arguments[2] as IntByReference).value = transferredBytes.get()
                true
            } else {
                lastError.set(overlappedError)
                false
            }
        }

        else -> defaultReturn(method.returnType)
    }

    private fun defaultInvocation(
        method: java.lang.reflect.Method,
        @Suppress("UNUSED_PARAMETER") arguments: Array<out Any?>,
    ): Any? = defaultReturn(method.returnType)

    private fun invokeExtendedAdvapi32(
        method: java.lang.reflect.Method,
        arguments: Array<out Any?>,
    ): Any? = when (method.name) {
        "ConvertStringSecurityDescriptorToSecurityDescriptorW" -> {
            securityDescriptor = (arguments[0] as WString).toString()
            (arguments[2] as PointerByReference).value = Pointer(3)
            true
        }

        else -> defaultReturn(method.returnType)
    }

    private fun defaultReturn(type: Class<*>): Any? = when (type) {
        java.lang.Boolean.TYPE -> false
        java.lang.Integer.TYPE -> 0
        java.lang.Long.TYPE -> 0L
        java.lang.Void.TYPE -> null
        else -> null
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> proxy(
        type: Class<T>,
        invocation: (java.lang.reflect.Method, Array<out Any?>) -> Any?,
    ): T = Proxy.newProxyInstance(
        type.classLoader,
        arrayOf(type),
        InvocationHandler { proxy, method, arguments ->
            when (method.name) {
                "toString" -> "Fake${type.simpleName}"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === arguments?.firstOrNull()
                else -> invocation(method, arguments ?: emptyArray())
            }
        },
    ) as T
}
