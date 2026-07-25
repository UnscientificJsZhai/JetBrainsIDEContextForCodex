package com.github.unscientificjszhai.codexjetbrainsideplugin.context

import com.intellij.openapi.vfs.VirtualFile
import java.nio.file.Path

object WorkspacePathMapper {
    fun toProtocolPath(file: VirtualFile, workspaceRoot: Path): String? {
        if (!file.isValid || !file.isInLocalFileSystem) return null
        val filePath = runCatching { file.toNioPath() }.getOrNull() ?: return null
        return toProtocolPath(filePath, workspaceRoot)
    }

    internal fun toProtocolPath(file: Path, workspaceRoot: Path): String? {
        val canonicalWorkspaceRoot = canonicalizeAbsolutePath(workspaceRoot) ?: return null
        val canonicalFile = canonicalizeAbsolutePath(file) ?: return null
        if (!canonicalFile.startsWith(canonicalWorkspaceRoot)) return null

        val relativePath = runCatching { canonicalWorkspaceRoot.relativize(canonicalFile) }.getOrNull()
            ?: return null
        if (relativePath.toString().isEmpty() || relativePath.isAbsolute) return null
        if (relativePath.any { it.toString() == ".." }) return null

        return relativePath.joinToString("/") { it.toString() }
            .takeIf { it.isNotEmpty() && !it.startsWith("/") }
    }
}
