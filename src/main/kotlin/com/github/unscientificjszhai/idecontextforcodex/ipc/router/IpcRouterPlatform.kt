package com.github.unscientificjszhai.idecontextforcodex.ipc.router

import com.github.unscientificjszhai.idecontextforcodex.ipc.transport.IpcConnection
import com.github.unscientificjszhai.idecontextforcodex.ipc.transport.IpcTransport

/**
 * 连接现有公开 Router endpoint 的结果。
 */
sealed interface IpcConnectResult {
    data class Connected(val connection: IpcConnection) : IpcConnectResult

    /**
     * 已确认 endpoint 不存在，允许竞选 fallback owner。
     */
    data object Absent : IpcConnectResult

    /**
     * endpoint 存在但暂时不可连接，禁止抢占并应退避重试。
     */
    data object RetryableConflict : IpcConnectResult
}

/**
 * 已取得的平台 Router ownership。
 */
interface IpcRouterOwnerSession : AutoCloseable {
    val transport: IpcTransport

    suspend fun connectProvider(): IpcConnection
}

/**
 * Router 协调器与平台 endpoint、ownership 和安全策略之间的边界。
 */
interface IpcRouterPlatform : AutoCloseable {
    suspend fun connectExisting(): IpcConnectResult

    suspend fun tryAcquireOwner(): IpcRouterOwnerSession?

    override fun close() = Unit
}
