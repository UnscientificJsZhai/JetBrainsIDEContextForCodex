package com.github.unscientificjszhai.idecontextforcodex.ipc.transport

import jdk.net.ExtendedSocketOptions
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.*

class UnsafeIpcEndpointException(message: String) : IOException(message)

/**
 * Unix endpoint 的 owner、类型与权限检查。
 *
 * 所有检查均禁止跟随最终路径的符号链接；异常文本只描述错误类别，不包含完整路径。
 */
class UnixEndpointSecurity(
    private val currentUser: UserPrincipal = Files.getOwner(Path.of(System.getProperty("user.home"))),
) {
    fun ensurePrivateDirectory(directory: Path) {
        var created = false
        if (!Files.exists(directory, NOFOLLOW_LINKS)) {
            Files.createDirectories(directory)
            created = true
        }

        val attributes = Files.readAttributes(directory, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        if (!attributes.isDirectory || attributes.isSymbolicLink) {
            throw UnsafeIpcEndpointException("IPC 父路径不是安全目录")
        }
        requireCurrentUserOwner(directory, "IPC 父目录 owner 不匹配")
        if (created) {
            setPermissions(directory, DIRECTORY_PERMISSIONS)
        }

        val permissions = readPermissions(directory)
        if (permissions.any { it in GROUP_OR_OTHER_WRITE }) {
            throw UnsafeIpcEndpointException("IPC 父目录允许 group/world 写入")
        }
    }

    fun verifyExistingSocket(socket: Path): Any? {
        val attributes = Files.readAttributes(socket, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        if (!attributes.isOther || attributes.isSymbolicLink) {
            throw UnsafeIpcEndpointException("IPC endpoint 不是 Unix socket")
        }
        requireCurrentUserOwner(socket, "IPC endpoint owner 不匹配")

        val permissions = readPermissions(socket)
        if (permissions.any { it in GROUP_OR_OTHER_PERMISSIONS }) {
            throw UnsafeIpcEndpointException("IPC endpoint 权限不是私有权限")
        }
        return attributes.fileKey()
    }

    fun removeVerifiedStaleSocket(socket: Path, expectedFileKey: Any?) {
        val actualFileKey = verifyExistingSocket(socket)
        if (expectedFileKey != null && actualFileKey != expectedFileKey) {
            throw UnsafeIpcEndpointException("IPC endpoint 在清理前发生变化")
        }
        Files.delete(socket)
    }

    fun setSocketPermissions(socket: Path) {
        try {
            // macOS 不支持对 Unix socket 使用 NOFOLLOW PosixFileAttributeView，
            // 该路径刚由受控 bind 创建且父目录已验证为私有目录。
            Files.setPosixFilePermissions(socket, SOCKET_PERMISSIONS)
        } catch (exception: UnsupportedOperationException) {
            throw UnsafeIpcEndpointException("当前文件系统不支持 POSIX 权限设置")
        }
    }

    fun verifyPeer(socket: SocketChannel) {
        val peer = try {
            socket.getOption(ExtendedSocketOptions.SO_PEERCRED)
        } catch (exception: UnsupportedOperationException) {
            throw UnsafeIpcEndpointException("当前平台不支持 Unix peer credential 检查")
        }
        if (peer.user() != currentUser) {
            throw UnsafeIpcEndpointException("IPC 对端用户不匹配")
        }
    }

    fun acquireOwnership(lockPath: Path): RouterOwnership? {
        ensurePrivateDirectory(lockPath.parent)
        val options: Set<OpenOption> = setOf(CREATE, WRITE, NOFOLLOW_LINKS)
        val channel = FileChannel.open(lockPath, options)
        runCatching {
            val attributes = Files.readAttributes(lockPath, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            if (!attributes.isRegularFile || attributes.isSymbolicLink) {
                throw UnsafeIpcEndpointException("IPC Router ownership lock 不是普通文件")
            }
            requireCurrentUserOwner(lockPath, "IPC Router ownership lock owner 不匹配")
            setPermissions(lockPath, SOCKET_PERMISSIONS)
        }
            .onFailure {
                channel.close()
                throw it
            }

        val lock = try {
            channel.tryLock()
        } catch (_: java.nio.channels.OverlappingFileLockException) {
            null
        }
        if (lock == null) {
            channel.close()
            return null
        }
        return RouterOwnership(channel, lock)
    }

    fun sameFile(socket: Path, expectedFileKey: Any?): Boolean {
        if (expectedFileKey == null || !Files.exists(socket, NOFOLLOW_LINKS)) return false
        return runCatching {
            Files.readAttributes(socket, BasicFileAttributes::class.java, NOFOLLOW_LINKS).fileKey() == expectedFileKey
        }.getOrDefault(false)
    }

    private fun requireCurrentUserOwner(path: Path, message: String) {
        if (Files.getOwner(path, NOFOLLOW_LINKS) != currentUser) {
            throw UnsafeIpcEndpointException(message)
        }
    }

    private fun readPermissions(path: Path): Set<PosixFilePermission> = try {
        Files.getPosixFilePermissions(path, NOFOLLOW_LINKS)
    } catch (exception: UnsupportedOperationException) {
        throw UnsafeIpcEndpointException("当前文件系统不支持 POSIX 权限检查")
    }

    private fun setPermissions(path: Path, permissions: Set<PosixFilePermission>) {
        try {
            val view = Files.getFileAttributeView(
                path,
                PosixFileAttributeView::class.java,
                NOFOLLOW_LINKS,
            ) ?: throw UnsafeIpcEndpointException("当前文件系统不支持 POSIX 权限设置")
            view.setPermissions(permissions)
        } catch (exception: UnsupportedOperationException) {
            throw UnsafeIpcEndpointException("当前文件系统不支持 POSIX 权限设置")
        }
    }

    companion object {
        private val DIRECTORY_PERMISSIONS = PosixFilePermissions.fromString("rwx------")
        private val SOCKET_PERMISSIONS = PosixFilePermissions.fromString("rw-------")
        private val GROUP_OR_OTHER_WRITE = setOf(
            PosixFilePermission.GROUP_WRITE,
            PosixFilePermission.OTHERS_WRITE,
        )
        private val GROUP_OR_OTHER_PERMISSIONS = setOf(
            PosixFilePermission.GROUP_READ,
            PosixFilePermission.GROUP_WRITE,
            PosixFilePermission.GROUP_EXECUTE,
            PosixFilePermission.OTHERS_READ,
            PosixFilePermission.OTHERS_WRITE,
            PosixFilePermission.OTHERS_EXECUTE,
        )
    }
}

class RouterOwnership internal constructor(
    private val channel: FileChannel,
    private val lock: FileLock,
) : AutoCloseable {
    override fun close() {
        runCatching { lock.release() }
        runCatching { channel.close() }
    }
}
