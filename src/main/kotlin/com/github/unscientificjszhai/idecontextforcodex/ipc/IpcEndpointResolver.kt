package com.github.unscientificjszhai.idecontextforcodex.ipc

import java.nio.file.Files
import java.nio.file.Path

data class IpcEndpoints(
    val codexHome: Path,
    val primary: Path,
    val legacy: List<Path>,
    val ownershipLock: Path,
) {
    val candidates: List<Path>
        get() = listOf(primary) + legacy
}

/**
 * 解析 IDEContextForCodex IPC 使用的本地端点。
 *
 * 主端点始终位于 Codex 主目录。临时目录地址只用于兼容旧版本，且仅在能够安全获得
 * 当前 Unix uid 时生成。
 */
class IpcEndpointResolver(
    private val environment: Map<String, String> = System.getenv(),
    private val userHome: Path = Path.of(System.getProperty("user.home")),
    private val tempDirectory: Path = resolveProcessTempDirectory(environment),
    private val uidProvider: () -> Long? = { resolveCurrentUid(userHome) },
) {
    fun resolve(codexHomeOverride: String? = null): IpcEndpoints {
        val codexHome = sequenceOf(codexHomeOverride, environment["CODEX_HOME"])
            .mapNotNull { value -> value?.trim()?.takeIf(String::isNotEmpty) }
            .map { value -> Path.of(value).toAbsolutePath().normalize() }
            .firstOrNull()
            ?: userHome.resolve(".codex").toAbsolutePath().normalize()

        val ipcDirectory = codexHome.resolve("ipc")
        val uid = uidProvider()
        val legacyDirectory = tempDirectory.toAbsolutePath().normalize().resolve("codex-ipc")
        val legacy = when {
            uid == null -> emptyList()
            uid == 0L -> listOf(
                legacyDirectory.resolve("ipc.sock"),
                legacyDirectory.resolve("ipc-0.sock"),
            )

            else -> listOf(legacyDirectory.resolve("ipc-$uid.sock"))
        }

        return IpcEndpoints(
            codexHome = codexHome,
            primary = ipcDirectory.resolve("ipc.sock"),
            legacy = legacy,
            ownershipLock = ipcDirectory.resolve("jetbrains-router.lock"),
        )
    }

    companion object {
        private fun resolveProcessTempDirectory(environment: Map<String, String>): Path {
            val configured = environment["TMPDIR"]?.takeIf(String::isNotBlank)
                ?: System.getProperty("java.io.tmpdir")
            return Path.of(configured)
        }

        private fun resolveCurrentUid(userHome: Path): Long? = runCatching {
            (Files.getAttribute(userHome, "unix:uid") as Number).toLong()
        }.getOrNull()
    }
}
