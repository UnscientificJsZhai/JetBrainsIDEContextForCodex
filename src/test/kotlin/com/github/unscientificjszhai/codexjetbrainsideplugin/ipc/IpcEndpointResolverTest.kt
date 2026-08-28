package com.github.unscientificjszhai.codexjetbrainsideplugin.ipc

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Path

class IpcEndpointResolverTest {
    @Test
    fun `显式覆盖优先于环境变量和默认目录`() {
        val resolver = IpcEndpointResolver(
            environment = mapOf("CODEX_HOME" to "/env/codex"),
            userHome = Path.of("/users/tester"),
            tempDirectory = Path.of("/tmp/process"),
            uidProvider = { 501L },
        )

        val endpoints = resolver.resolve("/override/codex")

        assertEquals(Path.of("/override/codex"), endpoints.codexHome)
        assertEquals(Path.of("/override/codex/ipc/ipc.sock"), endpoints.primary)
        assertEquals(
            listOf(Path.of("/tmp/process/codex-ipc/ipc-501.sock")),
            endpoints.legacy,
        )
        assertEquals(
            Path.of("/override/codex/ipc/jetbrains-router.lock"),
            endpoints.ownershipLock,
        )
    }

    @Test
    fun `环境变量优先于用户默认目录`() {
        val resolver = IpcEndpointResolver(
            environment = mapOf("CODEX_HOME" to "/env/codex"),
            userHome = Path.of("/users/tester"),
            tempDirectory = Path.of("/tmp/process"),
            uidProvider = { 1000L },
        )

        assertEquals(Path.of("/env/codex"), resolver.resolve().codexHome)
    }

    @Test
    fun `root 生成两个 legacy 回退地址`() {
        val resolver = IpcEndpointResolver(
            environment = emptyMap(),
            userHome = Path.of("/users/root"),
            tempDirectory = Path.of("/tmp/process"),
            uidProvider = { 0L },
        )

        assertEquals(
            listOf(
                Path.of("/tmp/process/codex-ipc/ipc.sock"),
                Path.of("/tmp/process/codex-ipc/ipc-0.sock"),
            ),
            resolver.resolve().legacy,
        )
    }

    @Test
    fun `候选端点始终按 primary 到 legacy 排序`() {
        val regularUserEndpoints = IpcEndpointResolver(
            environment = emptyMap(),
            userHome = Path.of("/users/tester"),
            tempDirectory = Path.of("/tmp/process"),
            uidProvider = { 501L },
        ).resolve("/codex/home")
        val rootEndpoints = IpcEndpointResolver(
            environment = emptyMap(),
            userHome = Path.of("/users/root"),
            tempDirectory = Path.of("/tmp/process"),
            uidProvider = { 0L },
        ).resolve("/codex/home")

        assertEquals(
            listOf(
                Path.of("/codex/home/ipc/ipc.sock"),
                Path.of("/tmp/process/codex-ipc/ipc-501.sock"),
            ),
            regularUserEndpoints.candidates,
        )
        assertEquals(
            listOf(
                Path.of("/codex/home/ipc/ipc.sock"),
                Path.of("/tmp/process/codex-ipc/ipc.sock"),
                Path.of("/tmp/process/codex-ipc/ipc-0.sock"),
            ),
            rootEndpoints.candidates,
        )
    }

    @Test
    fun `无法确认 uid 时不猜测 legacy 地址`() {
        val resolver = IpcEndpointResolver(
            environment = emptyMap(),
            userHome = Path.of("/users/tester"),
            tempDirectory = Path.of("/tmp/process"),
            uidProvider = { null },
        )

        assertEquals(emptyList<Path>(), resolver.resolve().legacy)
    }
}
