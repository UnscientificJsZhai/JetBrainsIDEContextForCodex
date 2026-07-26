package com.github.unscientificjszhai.codexjetbrainsideplugin.ipc.transport

import com.google.gson.JsonObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.Assume.assumeNoException
import org.junit.rules.TemporaryFolder
import java.io.EOFException
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

class UnixDomainSocketTransportTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var scope: CoroutineScope

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `监听后可以收发长度前缀 JSON 并在关闭时删除 socket`() = runBlocking {
        val endpoint = endpoint()
        val transport = UnixDomainSocketTransport(endpoint, scope)
        transport.start { connection ->
            val request = connection.readMessage()
            connection.writeMessage(
                JsonObject().apply {
                    addProperty("echo", request["message"].asString)
                },
            )
        }

        UnixIpcConnection.connect(endpoint).use { connection ->
            connection.writeMessage(JsonObject().apply { addProperty("message", "你好") })
            assertEquals("你好", withTimeout(2_000) { connection.readMessage() }["echo"].asString)
        }

        val permissions = Files.getPosixFilePermissions(endpoint)
        assertEquals(PosixFilePermissions.fromString("rw-------"), permissions)
        transport.close()
        assertFalse(Files.exists(endpoint))
    }

    @Test
    fun `第二个监听者不能删除活跃 owner 的 endpoint`() = runBlocking {
        val endpoint = endpoint()
        val owner = UnixDomainSocketTransport(endpoint, scope)
        val contender = UnixDomainSocketTransport(endpoint, scope)
        owner.start { connection -> connection.readMessage() }

        assertThrows(IOException::class.java) {
            runBlocking { contender.start { } }
        }
        contender.close()

        UnixIpcConnection.connect(endpoint).close()
        owner.close()
    }

    @Test
    fun `父目录允许 group 写入时拒绝监听`() {
        val endpoint = endpoint()
        Files.createDirectories(endpoint.parent)
        Files.setPosixFilePermissions(endpoint.parent, PosixFilePermissions.fromString("rwxrwx---"))
        val transport = UnixDomainSocketTransport(endpoint, scope)

        assertThrows(UnsafeIpcEndpointException::class.java) {
            runBlocking { transport.start { } }
        }
        assertFalse(Files.exists(endpoint))
    }

    @Test
    fun `符号链接父目录会在修改权限前被拒绝`() {
        val root = temporaryFolder.root.toPath()
        val realDirectory = root.resolve("real-ipc")
        val linkedDirectory = root.resolve("linked-ipc")
        Files.createDirectories(realDirectory)
        try {
            Files.createSymbolicLink(linkedDirectory, realDirectory)
        } catch (exception: UnsupportedOperationException) {
            assumeNoException(exception)
        } catch (exception: IOException) {
            assumeNoException(exception)
        }
        val originalPermissions = Files.getPosixFilePermissions(realDirectory)
        val transport = UnixDomainSocketTransport(linkedDirectory.resolve("ipc.sock"), scope)

        assertThrows(UnsafeIpcEndpointException::class.java) {
            runBlocking { transport.start { } }
        }
        assertEquals(originalPermissions, Files.getPosixFilePermissions(realDirectory))
    }

    @Test
    fun `第十七个连接会在默认上限后立即关闭`() = runBlocking {
        val endpoint = endpoint()
        val transport = UnixDomainSocketTransport(endpoint, scope)
        transport.start { connection ->
            connection.readMessage()
        }

        val accepted = List(16) { UnixIpcConnection.connect(endpoint) }
        delay(100)
        val overflow = UnixIpcConnection.connect(endpoint)
        try {
            assertThrows(EOFException::class.java) {
                runBlocking {
                    withTimeout(2_000) {
                        overflow.readMessage()
                    }
                }
            }
        } finally {
            accepted.forEach(UnixIpcConnection::close)
            overflow.close()
            transport.close()
        }
        Unit
    }

    @Test
    fun `启动协程取消不会残留 endpoint`() = runBlocking {
        repeat(25) { index ->
            val endpoint = temporaryFolder.root.toPath().resolve("cancel-$index/ipc.sock")
            val transport = UnixDomainSocketTransport(endpoint, scope)
            val startJob = scope.launch {
                transport.start { connection -> connection.readMessage() }
            }

            startJob.cancelAndJoin()
            transport.close()

            assertFalse("第 $index 次启动取消后仍残留 endpoint", Files.exists(endpoint))
        }
    }

    @Test
    fun `关闭后必须使用新 transport 实例重新监听`() = runBlocking {
        val endpoint = endpoint()
        val first = UnixDomainSocketTransport(endpoint, scope)

        first.start { connection -> connection.readMessage() }
        first.close()
        assertThrows(IOException::class.java) {
            runBlocking { first.start { connection -> connection.readMessage() } }
        }

        val second = UnixDomainSocketTransport(endpoint, scope)
        second.start { connection -> connection.readMessage() }

        UnixIpcConnection.connect(endpoint).close()
        second.close()
        assertFalse(Files.exists(endpoint))
    }

    @Test
    fun `ownership lock 在同一进程中保持互斥`() {
        val endpoint = endpoint()
        val security = UnixEndpointSecurity()
        val lockPath = endpoint.parent.resolve("router.lock")

        val first = security.acquireOwnership(lockPath)
        try {
            assertEquals(null, security.acquireOwnership(lockPath))
        } finally {
            first?.close()
        }
        security.acquireOwnership(lockPath)?.close()
    }

    @Test
    fun `ownership lock 符号链接不会修改目标文件权限`() {
        val endpoint = endpoint()
        Files.createDirectories(endpoint.parent)
        Files.setPosixFilePermissions(endpoint.parent, PosixFilePermissions.fromString("rwx------"))
        val target = temporaryFolder.root.toPath().resolve("target.txt")
        Files.writeString(target, "不可修改")
        Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rw-r-----"))
        val lockPath = endpoint.parent.resolve("router.lock")
        try {
            Files.createSymbolicLink(lockPath, target)
        } catch (exception: UnsupportedOperationException) {
            assumeNoException(exception)
        } catch (exception: IOException) {
            assumeNoException(exception)
        }
        val security = UnixEndpointSecurity()

        assertThrows(IOException::class.java) {
            security.acquireOwnership(lockPath)
        }
        assertEquals(
            PosixFilePermissions.fromString("rw-r-----"),
            Files.getPosixFilePermissions(target),
        )
    }

    private fun endpoint(): Path =
        temporaryFolder.root.toPath().resolve("codex/ipc/ipc.sock")
}
