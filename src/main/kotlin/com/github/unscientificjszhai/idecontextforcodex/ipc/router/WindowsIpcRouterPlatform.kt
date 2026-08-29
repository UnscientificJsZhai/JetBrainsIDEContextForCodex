package com.github.unscientificjszhai.idecontextforcodex.ipc.router

import com.github.unscientificjszhai.idecontextforcodex.ipc.protocol.IpcConstants
import com.github.unscientificjszhai.idecontextforcodex.ipc.transport.IpcConnection
import com.github.unscientificjszhai.idecontextforcodex.ipc.transport.IpcTransport
import com.github.unscientificjszhai.idecontextforcodex.ipc.transport.windows.*
import kotlinx.coroutines.CoroutineScope
import java.io.IOException

/**
 * 固定 endpoint 的 Windows named-pipe Router 平台实现。
 */
class WindowsIpcRouterPlatform(
    private val coroutineScope: CoroutineScope,
    private val adapter: WindowsNativePipeAdapter = JnaWindowsNativePipeAdapter(),
) : IpcRouterPlatform {
    override suspend fun connectExisting(): IpcConnectResult =
        when (val result = adapter.openClient(IpcConstants.REQUEST_TIMEOUT_MS)) {
            is WindowsClientOpenResult.Connected -> {
                try {
                    adapter.verifyPeer(result.handle, WindowsPipePeer.SERVER)
                    IpcConnectResult.Connected(clientConnection(result.handle))
                } catch (exception: Throwable) {
                    result.handle.close()
                    throw exception
                }
            }

            WindowsClientOpenResult.Absent -> IpcConnectResult.Absent
            WindowsClientOpenResult.Busy -> IpcConnectResult.RetryableConflict
        }

    override suspend fun tryAcquireOwner(): IpcRouterOwnerSession? =
        when (val result = adapter.createServer(firstInstance = true)) {
            is WindowsServerCreateResult.Created -> {
                val transport = WindowsNamedPipeTransport(
                    initialListener = result.handle,
                    adapter = adapter,
                    coroutineScope = coroutineScope,
                )
                WindowsRouterOwnerSession(transport, adapter)
            }

            WindowsServerCreateResult.Conflict -> null
        }

    override fun close() {
        adapter.close()
    }

    private fun clientConnection(handle: WindowsPipeHandle): IpcConnection =
        WindowsNamedPipeConnection(
            handle = handle,
            adapter = adapter,
            serverSide = false,
        )
}

private class WindowsRouterOwnerSession(
    override val transport: IpcTransport,
    private val adapter: WindowsNativePipeAdapter,
) : IpcRouterOwnerSession {
    override suspend fun connectProvider(): IpcConnection =
        when (val result = adapter.openClient(IpcConstants.REQUEST_TIMEOUT_MS)) {
            is WindowsClientOpenResult.Connected -> {
                try {
                    adapter.verifyPeer(result.handle, WindowsPipePeer.SERVER)
                    WindowsNamedPipeConnection(
                        handle = result.handle,
                        adapter = adapter,
                        serverSide = false,
                    )
                } catch (exception: Throwable) {
                    result.handle.close()
                    throw exception
                }
            }

            WindowsClientOpenResult.Absent ->
                throw IOException("Windows Router owner 启动后 endpoint 不存在")

            WindowsClientOpenResult.Busy ->
                throw IOException("Windows Router owner 启动后 endpoint 持续繁忙")
        }

    override fun close() {
        transport.close()
    }
}
