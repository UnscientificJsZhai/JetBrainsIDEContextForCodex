package com.github.unscientificjszhai.codexjetbrainsideplugin.ipc.router

import com.github.unscientificjszhai.codexjetbrainsideplugin.ipc.IdeContextProvider
import com.github.unscientificjszhai.codexjetbrainsideplugin.ipc.IpcEndpoints
import com.github.unscientificjszhai.codexjetbrainsideplugin.ipc.protocol.IpcConstants
import com.github.unscientificjszhai.codexjetbrainsideplugin.ipc.protocol.IpcMessages
import com.github.unscientificjszhai.codexjetbrainsideplugin.ipc.protocol.obj
import com.github.unscientificjszhai.codexjetbrainsideplugin.ipc.protocol.string
import com.github.unscientificjszhai.codexjetbrainsideplugin.ipc.transport.UnsafeIpcEndpointException
import com.github.unscientificjszhai.codexjetbrainsideplugin.ipc.transport.UnixIpcConnection
import com.google.gson.JsonObject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.ConnectException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.io.IOException
import java.nio.channels.ServerSocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID

class IpcRouterIntegrationTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

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
    fun `Router 发现 provider 后转发原始请求和响应`() = runBlocking {
        val endpoint = endpoint()
        val router = JetBrainsIpcRouter(endpoint, scope)
        router.start()
        jobs += scope.launch {
            CodexRouterClient(FakeProvider(canHandle = true)).serve(endpoint)
        }
        awaitProviders(router, 1)

        val response = sendIdeContextRequest(endpoint)

        assertEquals("success", response.string("resultType"))
        assertTrue(response.string("handledByClientId")!!.startsWith("jetbrains-provider-"))
        assertEquals("ok", response["result"].asJsonObject["marker"].asString)
        router.close()
    }

    @Test
    fun `没有 provider 声明 workspace 时返回 no-client-found`() = runBlocking {
        val endpoint = endpoint()
        val router = JetBrainsIpcRouter(endpoint, scope)
        router.start()
        jobs += scope.launch {
            CodexRouterClient(FakeProvider(canHandle = false)).serve(endpoint)
        }
        awaitProviders(router, 1)

        val response = sendIdeContextRequest(endpoint)

        assertEquals("error", response.string("resultType"))
        assertEquals(IpcConstants.ERROR_NO_CLIENT_FOUND, response.string("error"))
        router.close()
    }

    @Test
    fun `Router 会继续发现后续能够处理 workspace 的 provider`() = runBlocking {
        val endpoint = endpoint()
        val router = JetBrainsIpcRouter(endpoint, scope)
        router.start()
        jobs += scope.launch {
            CodexRouterClient(FakeProvider(canHandle = false, marker = "first")).serve(endpoint)
        }
        awaitProviders(router, 1)
        jobs += scope.launch {
            CodexRouterClient(FakeProvider(canHandle = true, marker = "second")).serve(endpoint)
        }
        awaitProviders(router, 2)

        val response = sendIdeContextRequest(endpoint)

        assertEquals("success", response.string("resultType"))
        assertEquals("second", response["result"].asJsonObject["marker"].asString)
        router.close()
    }

    @Test
    fun `多个 provider 同时声明可处理时安全返回 no-client-found`() = runBlocking {
        val endpoint = endpoint()
        val router = JetBrainsIpcRouter(endpoint, scope)
        router.start()
        jobs += scope.launch {
            CodexRouterClient(FakeProvider(canHandle = true, marker = "first")).serve(endpoint)
        }
        awaitProviders(router, 1)
        jobs += scope.launch {
            CodexRouterClient(FakeProvider(canHandle = true, marker = "second")).serve(endpoint)
        }
        awaitProviders(router, 2)

        val response = sendIdeContextRequest(endpoint)

        assertEquals("error", response.string("resultType"))
        assertEquals(IpcConstants.ERROR_NO_CLIENT_FOUND, response.string("error"))
        router.close()
    }

    @Test
    fun `无响应候选不会阻塞健康 provider 到全局 deadline`() = runBlocking {
        val endpoint = endpoint()
        val router = JetBrainsIpcRouter(endpoint, scope)
        router.start()
        jobs += scope.launch {
            CodexRouterClient(
                FakeProvider(canHandle = false, marker = "slow", discoveryDelayMs = 5_000),
            ).serve(endpoint)
        }
        awaitProviders(router, 1)
        jobs += scope.launch {
            CodexRouterClient(FakeProvider(canHandle = true, marker = "healthy")).serve(endpoint)
        }
        awaitProviders(router, 2)

        val startedAt = System.nanoTime()
        val response = sendIdeContextRequest(endpoint)
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000

        assertEquals("success", response.string("resultType"))
        assertEquals("healthy", response["result"].asJsonObject["marker"].asString)
        assertTrue("发现耗时不应达到全局 deadline，实际 ${elapsedMs}ms", elapsedMs < 2_500)
        router.close()
    }

    @Test
    fun `initialize 响应始终先于后续 discovery 帧`() = runBlocking {
        val endpoint = endpoint()
        val router = JetBrainsIpcRouter(endpoint, scope)
        router.start()
        UnixIpcConnection.connect(endpoint).use { providerConnection ->
            val initialize = IpcMessages.initializeRequest("initialize-order")
            providerConnection.writeMessage(initialize)
            withTimeout(2_000) {
                while (router.registeredClientCount == 0) delay(10)
            }

            val tuiResponse = async { sendIdeContextRequest(endpoint) }
            val initializeResponse = providerConnection.readMessage()
            val discovery = providerConnection.readMessage()

            assertEquals("initialize-order", initializeResponse.string("requestId"))
            assertEquals(IpcMessages.TYPE_DISCOVERY_REQUEST, discovery.string("type"))
            providerConnection.writeMessage(
                IpcMessages.discoveryResponse(discovery.string("requestId")!!, true),
            )

            val originalRequest = providerConnection.readMessage()
            providerConnection.writeMessage(
                IpcMessages.successResponse(
                    requestId = originalRequest.string("requestId")!!,
                    method = IpcConstants.IDE_CONTEXT_METHOD,
                    handledByClientId = initializeResponse.obj("result")!!.string("clientId")!!,
                    result = JsonObject().apply { addProperty("marker", "ordered") },
                ),
            )
            assertEquals("success", tuiResponse.await().string("resultType"))
        }
        router.close()
    }

    @Test
    fun `Router 在发现前拒绝不兼容协议版本`() = runBlocking {
        val endpoint = endpoint()
        val router = JetBrainsIpcRouter(endpoint, scope)
        router.start()

        val response = sendIdeContextRequest(endpoint, version = 1)

        assertEquals("error", response.string("resultType"))
        assertEquals(IpcConstants.ERROR_REQUEST_VERSION_MISMATCH, response.string("error"))
        router.close()
    }

    @Test
    fun `Coordinator 在没有 Router 时安全成为 owner`() = runBlocking {
        val endpoint = endpoint()
        val endpoints = IpcEndpoints(
            codexHome = endpoint.parent.parent,
            primary = endpoint,
            legacy = emptyList(),
            ownershipLock = endpoint.parent.resolve("jetbrains-router.lock"),
        )
        val coordinator = IpcRouterCoordinator(
            endpoints = endpoints,
            provider = FakeProvider(canHandle = true),
            coroutineScope = scope,
        )
        jobs += scope.launch { coordinator.run() }
        withTimeout(3_000) {
            while (!Files.exists(endpoint) || !coordinator.isProviderConnected) delay(10)
        }

        val response = sendIdeContextRequest(endpoint)

        assertEquals("success", response.string("resultType"))
        coordinator.close()
        withTimeout(2_000) {
            while (Files.exists(endpoint)) delay(10)
        }
        assertFalse(Files.exists(endpoint))
    }

    @Test
    fun `Coordinator 优先连接已有 Router 而不替换 endpoint`() = runBlocking {
        val endpoint = endpoint()
        val router = JetBrainsIpcRouter(endpoint, scope)
        router.start()
        val originalFileKey = Files.readAttributes(
            endpoint,
            java.nio.file.attribute.BasicFileAttributes::class.java,
        ).fileKey()
        val endpoints = IpcEndpoints(
            codexHome = endpoint.parent.parent,
            primary = endpoint,
            legacy = emptyList(),
            ownershipLock = endpoint.parent.resolve("jetbrains-router.lock"),
        )
        val coordinator = IpcRouterCoordinator(
            endpoints = endpoints,
            provider = FakeProvider(canHandle = true),
            coroutineScope = scope,
        )
        jobs += scope.launch { coordinator.run() }
        withTimeout(3_000) {
            while (router.registeredClientCount == 0) delay(10)
        }

        val response = sendIdeContextRequest(endpoint)
        val currentFileKey = Files.readAttributes(
            endpoint,
            java.nio.file.attribute.BasicFileAttributes::class.java,
        ).fileKey()

        assertEquals("success", response.string("resultType"))
        assertEquals(originalFileKey, currentFileKey)
        assertFalse(Files.exists(endpoints.ownershipLock))
        coordinator.close()
        router.close()
    }

    @Test
    fun `Coordinator 关闭时主动注销已有 Router 上的 provider`() = runBlocking {
        val endpoint = endpoint()
        val router = JetBrainsIpcRouter(endpoint, scope)
        router.start()
        val endpoints = IpcEndpoints(
            codexHome = endpoint.parent.parent,
            primary = endpoint,
            legacy = emptyList(),
            ownershipLock = endpoint.parent.resolve("jetbrains-router.lock"),
        )
        val coordinator = IpcRouterCoordinator(
            endpoints = endpoints,
            provider = FakeProvider(canHandle = true),
            coroutineScope = scope,
        )
        val coordinatorJob = scope.launch { coordinator.run() }
        jobs += coordinatorJob
        awaitProviders(router, 1)

        coordinator.close()

        withTimeout(3_000) { coordinatorJob.join() }
        awaitProviders(router, 0)
        assertTrue(router.isRunning)
        router.close()
    }

    @Test
    fun `Coordinator 仅在连接探测失败后清理同用户 stale socket`() = runBlocking {
        val endpoint = endpoint()
        Files.createDirectories(endpoint.parent)
        Files.setPosixFilePermissions(endpoint.parent, PosixFilePermissions.fromString("rwx------"))
        ServerSocketChannel.open(StandardProtocolFamily.UNIX).use { staleServer ->
            staleServer.bind(UnixDomainSocketAddress.of(endpoint))
            Files.setPosixFilePermissions(endpoint, PosixFilePermissions.fromString("rw-------"))
        }
        assertTrue(Files.exists(endpoint))

        val endpoints = IpcEndpoints(
            codexHome = endpoint.parent.parent,
            primary = endpoint,
            legacy = emptyList(),
            ownershipLock = endpoint.parent.resolve("jetbrains-router.lock"),
        )
        val coordinator = IpcRouterCoordinator(
            endpoints = endpoints,
            provider = FakeProvider(canHandle = true),
            coroutineScope = scope,
        )
        jobs += scope.launch { coordinator.run() }
        withTimeout(3_000) {
            while (!coordinator.isProviderConnected) delay(10)
        }

        assertEquals("success", sendIdeContextRequest(endpoint).string("resultType"))
        coordinator.close()
    }

    @Test
    fun `安全探测失败时不会删除仍存在的 endpoint`() = runBlocking {
        val endpoint = createStaleSocket()
        val originalFileKey = Files.readAttributes(
            endpoint,
            java.nio.file.attribute.BasicFileAttributes::class.java,
        ).fileKey()
        val endpoints = IpcEndpoints(
            codexHome = endpoint.parent.parent,
            primary = endpoint,
            legacy = emptyList(),
            ownershipLock = endpoint.parent.resolve("jetbrains-router.lock"),
        )
        val coordinator = IpcRouterCoordinator(
            endpoints = endpoints,
            provider = FakeProvider(canHandle = true),
            coroutineScope = scope,
            endpointProbe = { throw UnsafeIpcEndpointException("模拟 peer credential 失败") },
        )
        jobs += scope.launch { coordinator.run() }

        delay(750)

        assertTrue(Files.exists(endpoint))
        assertEquals(
            originalFileKey,
            Files.readAttributes(
                endpoint,
                java.nio.file.attribute.BasicFileAttributes::class.java,
            ).fileKey(),
        )
        coordinator.close()
    }

    @Test
    fun `关闭发生在 stale 探测期间时不会删除 endpoint 或重新 bind`() = runBlocking {
        val endpoint = createStaleSocket()
        val originalFileKey = Files.readAttributes(
            endpoint,
            java.nio.file.attribute.BasicFileAttributes::class.java,
        ).fileKey()
        val endpoints = IpcEndpoints(
            codexHome = endpoint.parent.parent,
            primary = endpoint,
            legacy = emptyList(),
            ownershipLock = endpoint.parent.resolve("jetbrains-router.lock"),
        )
        val probeEntered = CompletableDeferred<Unit>()
        val releaseProbe = CompletableDeferred<Unit>()
        val coordinator = IpcRouterCoordinator(
            endpoints = endpoints,
            provider = FakeProvider(canHandle = true),
            coroutineScope = scope,
            endpointProbe = {
                probeEntered.complete(Unit)
                releaseProbe.await()
                throw ConnectException("模拟 connection refused")
            },
        )
        val coordinatorJob = scope.launch { coordinator.run() }
        jobs += coordinatorJob
        withTimeout(3_000) { probeEntered.await() }

        coordinator.close()
        releaseProbe.complete(Unit)
        withTimeout(3_000) { coordinatorJob.join() }

        assertTrue(Files.exists(endpoint))
        assertEquals(
            originalFileKey,
            Files.readAttributes(
                endpoint,
                java.nio.file.attribute.BasicFileAttributes::class.java,
            ).fileKey(),
        )
    }

    @Test
    fun `owner 退出后 follower 会接管 Router`() = runBlocking {
        val endpoint = endpoint()
        val endpoints = IpcEndpoints(
            codexHome = endpoint.parent.parent,
            primary = endpoint,
            legacy = emptyList(),
            ownershipLock = endpoint.parent.resolve("jetbrains-router.lock"),
        )
        val owner = IpcRouterCoordinator(
            endpoints = endpoints,
            provider = FakeProvider(canHandle = true, marker = "owner"),
            coroutineScope = scope,
        )
        jobs += scope.launch { owner.run() }
        withTimeout(3_000) {
            while (!owner.isProviderConnected) delay(10)
        }
        val ownerFileKey = Files.readAttributes(
            endpoint,
            java.nio.file.attribute.BasicFileAttributes::class.java,
        ).fileKey()

        val follower = IpcRouterCoordinator(
            endpoints = endpoints,
            provider = FakeProvider(canHandle = true, marker = "follower"),
            coroutineScope = scope,
        )
        jobs += scope.launch { follower.run() }
        withTimeout(3_000) {
            while (!follower.isProviderConnected) delay(10)
        }

        owner.close()
        withTimeout(6_000) {
            while (true) {
                val changed = runCatching {
                    Files.readAttributes(
                        endpoint,
                        java.nio.file.attribute.BasicFileAttributes::class.java,
                    ).fileKey() != ownerFileKey
                }.getOrDefault(false)
                if (changed && follower.isProviderConnected) break
                delay(25)
            }
        }

        val response = sendIdeContextRequest(endpoint)
        assertEquals("success", response.string("resultType"))
        assertEquals("follower", response["result"].asJsonObject["marker"].asString)
        follower.close()
    }

    @Test
    fun `TUI 一次性连接处理一个请求后关闭`() = runBlocking {
        val endpoint = endpoint()
        val router = JetBrainsIpcRouter(endpoint, scope)
        router.start()
        jobs += scope.launch {
            CodexRouterClient(FakeProvider(canHandle = true)).serve(endpoint)
        }
        awaitProviders(router, 1)

        UnixIpcConnection.connect(endpoint).use { connection ->
            connection.writeMessage(ideContextRequest("first"))
            assertEquals("success", connection.readMessage().string("resultType"))

            assertThrows(IOException::class.java) {
                runBlocking {
                    connection.writeMessage(ideContextRequest("second"))
                    connection.readMessage()
                }
            }
        }
        router.close()
    }

    private suspend fun sendIdeContextRequest(
        endpoint: Path,
        version: Int = IpcConstants.PROTOCOL_VERSION,
    ): JsonObject {
        val request = ideContextRequest(UUID.randomUUID().toString(), version)
        return UnixIpcConnection.connect(endpoint).use { connection ->
            connection.writeMessage(request)
            withTimeout(3_000) { connection.readMessage() }
        }
    }

    private fun ideContextRequest(
        requestId: String,
        version: Int = IpcConstants.PROTOCOL_VERSION,
    ): JsonObject = JsonObject().apply {
            addProperty("type", IpcMessages.TYPE_REQUEST)
            addProperty("requestId", requestId)
            addProperty("sourceClientId", "codex-tui")
            addProperty("version", version)
            addProperty("method", IpcConstants.IDE_CONTEXT_METHOD)
            add(
                "params",
                JsonObject().apply {
                    addProperty("workspaceRoot", "/workspace/repository")
                },
            )
        }

    private suspend fun awaitProviders(router: JetBrainsIpcRouter, count: Int) {
        withTimeout(3_000) {
            while (router.registeredClientCount != count) delay(10)
        }
    }

    private fun endpoint(): Path =
        temporaryFolder.root.toPath().resolve("codex/ipc/ipc.sock")

    private fun createStaleSocket(): Path {
        val endpoint = endpoint()
        Files.createDirectories(endpoint.parent)
        Files.setPosixFilePermissions(endpoint.parent, PosixFilePermissions.fromString("rwx------"))
        ServerSocketChannel.open(StandardProtocolFamily.UNIX).use { staleServer ->
            staleServer.bind(UnixDomainSocketAddress.of(endpoint))
            Files.setPosixFilePermissions(endpoint, PosixFilePermissions.fromString("rw-------"))
        }
        return endpoint
    }

    private class FakeProvider(
        private val canHandle: Boolean,
        private val marker: String = "ok",
        private val discoveryDelayMs: Long = 0,
    ) : IdeContextProvider {
        override suspend fun canHandle(request: JsonObject): Boolean {
            if (discoveryDelayMs > 0) delay(discoveryDelayMs)
            return canHandle
        }

        override suspend fun handle(request: JsonObject, clientId: String): JsonObject =
            IpcMessages.successResponse(
                requestId = request.string("requestId")!!,
                method = IpcConstants.IDE_CONTEXT_METHOD,
                handledByClientId = clientId,
                result = JsonObject().apply { addProperty("marker", marker) },
            )
    }
}
