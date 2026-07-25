package com.github.unscientificjszhai.codexjetbrainsideplugin.context

import com.intellij.testFramework.LightVirtualFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assume.assumeNoException
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class WorkspacePathMapperTest {
    @Test
    fun `VirtualFile wrapper 拒绝非本地文件`() {
        val workspace = Files.createTempDirectory("codex-workspace-")
        assertNull(
            WorkspacePathMapper.toProtocolPath(
                LightVirtualFile("Scratch.kt", "secret"),
                workspace,
            ),
        )
    }

    @Test
    fun `映射 workspace 内文件并统一分隔符`() {
        val workspace = Files.createTempDirectory("codex-workspace-")
        val file = Files.createDirectories(workspace.resolve("src/main")).resolve("Sample.kt")
        Files.writeString(file, "class Sample")

        assertEquals(
            "src/main/Sample.kt",
            WorkspacePathMapper.toProtocolPath(file, workspace),
        )
    }

    @Test
    fun `拒绝 workspace 本身与外部文件和相对 root`() {
        val workspace = Files.createTempDirectory("codex-workspace-")
        val outside = Files.createTempFile("codex-outside-", ".kt")

        assertNull(WorkspacePathMapper.toProtocolPath(workspace, workspace))
        assertNull(WorkspacePathMapper.toProtocolPath(outside, workspace))
        assertNull(WorkspacePathMapper.toProtocolPath(outside, Path.of("relative")))
    }

    @Test
    fun `symlink 指向 workspace 外时拒绝`() {
        val workspace = Files.createTempDirectory("codex-workspace-")
        val outside = Files.createTempFile("codex-outside-", ".kt")
        val link = workspace.resolve("escaped.kt")
        try {
            Files.createSymbolicLink(link, outside)
        } catch (exception: UnsupportedOperationException) {
            assumeNoException(exception)
        } catch (exception: java.io.IOException) {
            assumeNoException(exception)
        } catch (exception: SecurityException) {
            assumeNoException(exception)
        }

        assertNull(WorkspacePathMapper.toProtocolPath(link, workspace))
    }

    @Test
    fun `symlink 指向 workspace 内时使用 canonical 相对路径`() {
        val workspace = Files.createTempDirectory("codex-workspace-")
        val target = Files.createDirectories(workspace.resolve("real")).resolve("Target.kt")
        Files.writeString(target, "class Target")
        val link = workspace.resolve("Alias.kt")
        try {
            Files.createSymbolicLink(link, target)
        } catch (exception: UnsupportedOperationException) {
            assumeNoException(exception)
        } catch (exception: java.io.IOException) {
            assumeNoException(exception)
        } catch (exception: SecurityException) {
            assumeNoException(exception)
        }

        assertEquals(
            "real/Target.kt",
            WorkspacePathMapper.toProtocolPath(link, workspace),
        )
    }

    @Test
    fun `不存在的路径与大小写错误路径不回退到词法规范化`() {
        val workspace = Files.createTempDirectory("CodexCaseWorkspace-")
        val missing = workspace.resolve("Missing.kt")
        val wrongCaseRoot = workspace.parent.resolve(workspace.fileName.toString().swapCase())

        assertNull(WorkspacePathMapper.toProtocolPath(missing, workspace))
        if (wrongCaseRoot != workspace && !Files.exists(wrongCaseRoot)) {
            assertNull(WorkspacePathMapper.toProtocolPath(workspace, wrongCaseRoot))
        }
    }

    private fun String.swapCase(): String = map { character ->
        when {
            character.isLowerCase() -> character.uppercaseChar()
            character.isUpperCase() -> character.lowercaseChar()
            else -> character
        }
    }.joinToString("")
}
