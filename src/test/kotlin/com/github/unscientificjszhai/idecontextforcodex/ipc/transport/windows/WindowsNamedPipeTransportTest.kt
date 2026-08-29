package com.github.unscientificjszhai.idecontextforcodex.ipc.transport.windows

import com.github.unscientificjszhai.idecontextforcodex.ipc.IdeContextProvider
import com.github.unscientificjszhai.idecontextforcodex.ipc.protocol.FrameProtocolException
import com.github.unscientificjszhai.idecontextforcodex.ipc.protocol.IpcConstants
import com.github.unscientificjszhai.idecontextforcodex.ipc.protocol.IpcMessages
import com.github.unscientificjszhai.idecontextforcodex.ipc.protocol.LengthPrefixedJsonCodec
import com.github.unscientificjszhai.idecontextforcodex.ipc.protocol.string
import com.github.unscientificjszhai.idecontextforcodex.ipc.router.IpcConnectResult
import com.github.unscientificjszhai.idecontextforcodex.ipc.router.IpcRouterCoordinator
import com.github.unscientificjszhai.idecontextforcodex.ipc.router.WindowsIpcRouterPlatform
import com.github.unscientificjszhai.idecontextforcodex.ipc.transport.UnsafeIpcEndpointException
import com.google.gson.JsonObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.EOFException
import java.io.IOException
import java.util.UUID

class WindowsNamedPipeTransportTest {
    private lateinit var scope: CoroutineScope
    private val jobs = mutableListOf<Job>()

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @After
    fun tearDown() {
        jobs.forEach(Job::cancel)
        scope.cancel()
    }

    @Test
    fun `partial IO 仍可收发长度前缀 JSON`() = runBlocking {
        val adapter = FakeWindowsNativePipeAdapter().apply {
            partialReadLimit = 1
            partialWriteLimit = 1
        }
        val initial = createdHandle(adapter.createServer(firstInstance = true))
        val transport = WindowsNamedPipeTransport(initial, adapter, scope)
        transport.start { connection ->
            val request = connection.readMessage()
            connection.writeMessage(
                JsonObject().apply {
                    addProperty("echo", request.string("message"))
                },
            )
        }

        openConnection(adapter).use { connection ->
            connection.writeMessage(JsonObject().apply { addProperty("message", "你好") })
            assertEquals("你好", withTimeout(2_000) { connection.readMessage() }.string("echo"))
        }

        assertTrue(adapter.createPolicies.all { it == WindowsPipeAccessPolicy.CURRENT_USER_ONLY })
        transport.close()
        adapter.close()
        assertEquals(0, adapter.openHandleCount)
    }

    @Test
    fun `等待 client 支持 timeout 和 coroutine cancel`() = runBlocking {
        val adapter = FakeWindowsNativePipeAdapter()
        val timeoutHandle = createdHandle(adapter.createServer(firstInstance = true))

        assertThrows(WindowsPipeTimeoutException::class.java) {
            runBlocking { adapter.awaitClient(timeoutHandle, 25) }
        }
        timeoutHandle.close()

        val cancelHandle = createdHandle(adapter.createServer(firstInstance = true))
        val waitJob = launch {
            adapter.awaitClient(cancelHandle, Long.MAX_VALUE)
        }
        delay(25)
        waitJob.cancelAndJoin()

        assertTrue(adapter.cancelledWaits.get() >= 1)
        cancelHandle.close()
        adapter.close()
        assertEquals(0, adapter.openHandleCount)
    }

    @Test
    fun `SID 不匹配和身份查询失败会在读取 frame 前拒绝`() = runBlocking {
        val adapter = FakeWindowsNativePipeAdapter()
        val server = createdHandle(adapter.createServer(firstInstance = true))
        val platform = WindowsIpcRouterPlatform(scope, adapter)

        adapter.rejectPeer = true
        assertThrows(UnsafeIpcEndpointException::class.java) {
            runBlocking { platform.connectExisting() }
        }
        assertEquals(1, adapter.openHandleCount)

        adapter.rejectPeer = false
        adapter.identityLookupFails = true
        createdHandle(adapter.createServer(firstInstance = false))
        assertThrows(UnsafeIpcEndpointException::class.java) {
            runBlocking { platform.connectExisting() }
        }

        server.close()
        platform.close()
        assertEquals(0, adapter.openHandleCount)
    }

    @Test
    fun `existing Router 返回已验证的通用连接`() = runBlocking {
        val adapter = FakeWindowsNativePipeAdapter()
        createdHandle(adapter.createServer(firstInstance = true))
        val platform = WindowsIpcRouterPlatform(scope, adapter)

        val result = platform.connectExisting()

        assertTrue(result is IpcConnectResult.Connected)
        (result as IpcConnectResult.Connected).connection.close()
        assertEquals(1, adapter.peerVerificationCount.get())
        platform.close()
        assertEquals(0, adapter.openHandleCount)
    }

    @Test
    fun `busy endpoint 不会触发 fallback owner`() = runBlocking {
        val adapter = FakeWindowsNativePipeAdapter().apply { forceBusy = true }
        val platform = WindowsIpcRouterPlatform(scope, adapter)
        val coordinator = IpcRouterCoordinator(platform, FakeProvider())
        val job = scope.launch { coordinator.run() }
        jobs += job

        delay(750)

        assertEquals(0, adapter.firstInstanceAttempts.get())
        coordinator.close()
        withTimeout(2_000) { job.join() }
    }

    @Test
    fun `access denied 安全拒绝不会触发 fallback owner`() = runBlocking {
        val adapter = FakeWindowsNativePipeAdapter().apply {
            forceAccessDenied = true
        }
        val platform = WindowsIpcRouterPlatform(scope, adapter)
        val coordinator = IpcRouterCoordinator(platform, FakeProvider())
        val job = scope.launch { coordinator.run() }
        jobs += job

        delay(750)

        assertEquals(0, adapter.firstInstanceAttempts.get())
        coordinator.close()
        withTimeout(2_000) { job.join() }
    }

    @Test
    fun `first instance 冲突不会抢占现有 owner`() = runBlocking {
        val adapter = FakeWindowsNativePipeAdapter()
        val existingOwner = createdHandle(adapter.createServer(firstInstance = true))
        val platform = WindowsIpcRouterPlatform(scope, adapter)

        assertEquals(null, platform.tryAcquireOwner())
        assertEquals(2, adapter.firstInstanceAttempts.get())
        assertTrue(existingOwner.isOpen)

        existingOwner.close()
        platform.close()
    }

    @Test
    fun `owner session 关闭后 follower 才能原子取得 ownership`() = runBlocking {
        val adapter = FakeWindowsNativePipeAdapter()
        val ownerPlatform = WindowsIpcRouterPlatform(scope, adapter)
        val followerPlatform = WindowsIpcRouterPlatform(scope, adapter)
        val ownerSession = checkNotNull(ownerPlatform.tryAcquireOwner())

        assertEquals(null, followerPlatform.tryAcquireOwner())

        ownerSession.close()
        val followerSession = checkNotNull(followerPlatform.tryAcquireOwner())

        followerSession.close()
        followerPlatform.close()
        assertEquals(0, adapter.openHandleCount)
    }

    @Test
    fun `没有 Router 时 Windows coordinator 成为 owner 并完成协议路由`() = runBlocking {
        val adapter = FakeWindowsNativePipeAdapter()
        val platform = WindowsIpcRouterPlatform(scope, adapter)
        val coordinator = IpcRouterCoordinator(platform, FakeProvider(marker = "windows"))
        val coordinatorJob = scope.launch { coordinator.run() }
        jobs += coordinatorJob
        withTimeout(3_000) {
            while (!coordinator.isProviderConnected) delay(10)
        }

        val response = openConnection(adapter).use { connection ->
            connection.writeMessage(ideContextRequest())
            withTimeout(3_000) { connection.readMessage() }
        }

        assertEquals("success", response.string("resultType"))
        assertEquals("windows", response["result"].asJsonObject["marker"].asString)
        assertTrue(adapter.peerVerificationCount.get() >= 3)

        coordinator.close()
        withTimeout(3_000) { coordinatorJob.join() }
        assertEquals(0, adapter.openHandleCount)
    }

    @Test
    fun `第十七个业务连接会被关闭且关闭 transport 后 handle 归零`() = runBlocking {
        val adapter = FakeWindowsNativePipeAdapter()
        val initial = createdHandle(adapter.createServer(firstInstance = true))
        val transport = WindowsNamedPipeTransport(initial, adapter, scope)
        transport.start { connection ->
            connection.readMessage()
        }

        val accepted = List(16) { openConnection(adapter) }
        val overflow = openConnection(adapter)
        try {
            assertThrows(EOFException::class.java) {
                runBlocking {
                    withTimeout(2_000) {
                        overflow.readMessage()
                    }
                }
            }
        } finally {
            accepted.forEach(WindowsNamedPipeConnection::close)
            overflow.close()
            transport.close()
            adapter.close()
        }
        assertFalse(transport.isRunning)
        assertEquals(0, adapter.openHandleCount)
    }

    @Test
    fun `close 是终态且不能重新监听`() = runBlocking {
        val adapter = FakeWindowsNativePipeAdapter()
        val initial = createdHandle(adapter.createServer(firstInstance = true))
        val transport = WindowsNamedPipeTransport(initial, adapter, scope)

        transport.close()

        assertThrows(IOException::class.java) {
            runBlocking { transport.start { } }
        }
        adapter.close()
        assertFalse(transport.isRunning)
        assertEquals(0, adapter.openHandleCount)
    }

    @Test
    fun `close 与 standby 创建竞争不会重新发布 listener`() = runBlocking {
        val adapter = FakeWindowsNativePipeAdapter().apply {
            blockStandbyCreate = true
        }
        val initial = createdHandle(adapter.createServer(firstInstance = true))
        val transport = WindowsNamedPipeTransport(initial, adapter, scope)
        transport.start { }
        withTimeout(2_000) {
            while (adapter.standbyCreateEntered.count > 0) delay(10)
        }

        transport.close()
        adapter.releaseStandbyCreate.countDown()
        withTimeout(2_000) {
            while (adapter.openHandleCount != 0) delay(10)
        }

        adapter.close()
        assertFalse(transport.isRunning)
        assertEquals(0, adapter.openHandleCount)
    }

    @Test
    fun `超长 frame 在 Windows connection 层被 codec 拒绝`() = runBlocking {
        val adapter = FakeWindowsNativePipeAdapter()
        val serverHandle = createdHandle(adapter.createServer(firstInstance = true))
        val clientHandle = (
            adapter.openClient(IpcConstants.REQUEST_TIMEOUT_MS) as
                WindowsClientOpenResult.Connected
            ).handle
        adapter.awaitClient(serverHandle, IpcConstants.REQUEST_TIMEOUT_MS)
        val server = WindowsNamedPipeConnection(
            handle = serverHandle,
            adapter = adapter,
            serverSide = true,
            codec = LengthPrefixedJsonCodec(maxFrameBytes = 8),
        )
        val client = WindowsNamedPipeConnection(
            handle = clientHandle,
            adapter = adapter,
            serverSide = false,
        )

        client.writeMessage(JsonObject().apply { addProperty("payload", "过长") })
        assertThrows(FrameProtocolException::class.java) {
            runBlocking { server.readMessage() }
        }

        server.close()
        client.close()
        adapter.close()
        assertEquals(0, adapter.openHandleCount)
    }

    @Test
    fun `对端关闭后写入映射为 broken pipe`() = runBlocking {
        val adapter = FakeWindowsNativePipeAdapter()
        val serverHandle = createdHandle(adapter.createServer(firstInstance = true))
        val clientHandle = (
            adapter.openClient(IpcConstants.REQUEST_TIMEOUT_MS) as
                WindowsClientOpenResult.Connected
            ).handle
        adapter.awaitClient(serverHandle, IpcConstants.REQUEST_TIMEOUT_MS)
        val server = WindowsNamedPipeConnection(serverHandle, adapter, serverSide = true)
        val client = WindowsNamedPipeConnection(clientHandle, adapter, serverSide = false)
        server.close()

        assertThrows(IOException::class.java) {
            runBlocking {
                client.writeMessage(JsonObject().apply { addProperty("message", "closed") })
            }
        }

        client.close()
        adapter.close()
        assertEquals(0, adapter.openHandleCount)
    }

    private suspend fun openConnection(
        adapter: FakeWindowsNativePipeAdapter,
    ): WindowsNamedPipeConnection = withTimeout(3_000) {
        while (true) {
            when (val result = adapter.openClient(IpcConstants.REQUEST_TIMEOUT_MS)) {
                is WindowsClientOpenResult.Connected -> {
                    adapter.verifyPeer(result.handle, WindowsPipePeer.SERVER)
                    return@withTimeout WindowsNamedPipeConnection(
                        handle = result.handle,
                        adapter = adapter,
                        serverSide = false,
                    )
                }

                WindowsClientOpenResult.Absent,
                WindowsClientOpenResult.Busy,
                -> delay(10)
            }
        }
        @Suppress("UNREACHABLE_CODE")
        throw IllegalStateException("无法创建 fake Windows pipe 连接")
    }

    private fun createdHandle(result: WindowsServerCreateResult): WindowsPipeHandle =
        (result as WindowsServerCreateResult.Created).handle

    private fun ideContextRequest(): JsonObject = JsonObject().apply {
        addProperty("type", IpcMessages.TYPE_REQUEST)
        addProperty("requestId", UUID.randomUUID().toString())
        addProperty("sourceClientId", "codex-tui")
        addProperty("version", IpcConstants.PROTOCOL_VERSION)
        addProperty("method", IpcConstants.IDE_CONTEXT_METHOD)
        add(
            "params",
            JsonObject().apply {
                addProperty("workspaceRoot", "/workspace/repository")
            },
        )
    }

    private class FakeProvider(
        private val marker: String = "ok",
    ) : IdeContextProvider {
        override suspend fun canHandle(request: JsonObject): Boolean = true

        override suspend fun handle(request: JsonObject, clientId: String): JsonObject =
            IpcMessages.successResponse(
                requestId = request.string("requestId")!!,
                method = IpcConstants.IDE_CONTEXT_METHOD,
                handledByClientId = clientId,
                result = JsonObject().apply { addProperty("marker", marker) },
            )
    }
}
