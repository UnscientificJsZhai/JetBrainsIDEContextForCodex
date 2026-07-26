package com.github.unscientificjszhai.codexjetbrainsideplugin.ipc.transport

import com.google.gson.JsonObject

interface IpcConnection : AutoCloseable {
    val isOpen: Boolean

    suspend fun readMessage(): JsonObject

    suspend fun writeMessage(message: JsonObject)
}

interface IpcTransport : AutoCloseable {
    val isRunning: Boolean

    /**
     * 启动监听并为每个连接调用一次处理器。
     *
     * 方法在 endpoint 已经成功监听后返回；连接处理始终在后台执行。
     */
    suspend fun start(handler: suspend (IpcConnection) -> Unit)
}
