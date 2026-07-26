package com.github.unscientificjszhai.codexjetbrainsideplugin.ipc.router

import com.github.unscientificjszhai.codexjetbrainsideplugin.ipc.IdeContextProvider
import com.github.unscientificjszhai.codexjetbrainsideplugin.ipc.protocol.IpcConstants
import com.github.unscientificjszhai.codexjetbrainsideplugin.ipc.protocol.IpcMessages
import com.github.unscientificjszhai.codexjetbrainsideplugin.ipc.protocol.obj
import com.github.unscientificjszhai.codexjetbrainsideplugin.ipc.protocol.string
import com.github.unscientificjszhai.codexjetbrainsideplugin.ipc.transport.IpcConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 连接既有 IPC Router，把当前 JetBrains IDE 注册为 IDE Context provider。
 */
class CodexRouterClient(
    private val provider: IdeContextProvider,
) : AutoCloseable {
    private val lifecycleLock = Any()
    private val closed = AtomicBoolean(false)

    @Volatile
    var isInitialized: Boolean = false
        private set

    @Volatile
    private var activeConnection: IpcConnection? = null

    suspend fun serve(connection: IpcConnection) {
        if (closed.get()) {
            connection.close()
            return
        }
        val connectionPublished = synchronized(lifecycleLock) {
            if (closed.get()) {
                false
            } else {
                activeConnection = connection
                true
            }
        }
        if (!connectionPublished) {
            connection.close()
            return
        }

        try {
            connection.use {
                val initialize = IpcMessages.initializeRequest()
                val initializeId = initialize.string("requestId")!!
                connection.writeMessage(initialize)
                val clientId = awaitInitializeResponse(connection, initializeId)
                val initialized = synchronized(lifecycleLock) {
                    if (closed.get() || activeConnection !== connection) {
                        false
                    } else {
                        isInitialized = true
                        true
                    }
                }
                if (!initialized) return

                while (connection.isOpen) {
                    val message = connection.readMessage()
                    when (message.string("type")) {
                        IpcMessages.TYPE_DISCOVERY_REQUEST -> {
                            val requestId = message.string("requestId") ?: continue
                            val originalRequest = message.obj("request")
                            val canHandle = if (originalRequest == null) {
                                false
                            } else {
                                try {
                                    withTimeout(IpcConstants.REQUEST_TIMEOUT_MS) {
                                        provider.canHandle(originalRequest)
                                    }
                                } catch (exception: CancellationException) {
                                    if (exception is TimeoutCancellationException) false else throw exception
                                } catch (_: Throwable) {
                                    false
                                }
                            }
                            connection.writeMessage(IpcMessages.discoveryResponse(requestId, canHandle))
                        }

                        IpcMessages.TYPE_REQUEST -> {
                            val requestId = message.string("requestId") ?: continue
                            val response = try {
                                provider.handle(message, clientId)
                            } catch (exception: CancellationException) {
                                throw exception
                            } catch (_: Throwable) {
                                IpcMessages.errorResponse(
                                    requestId,
                                    IpcConstants.ERROR_NO_HANDLER_FOR_REQUEST,
                                )
                            }
                            if (response.size() == 0) {
                                connection.writeMessage(
                                    IpcMessages.errorResponse(
                                        requestId,
                                        IpcConstants.ERROR_NO_HANDLER_FOR_REQUEST,
                                    ),
                                )
                            } else {
                                connection.writeMessage(response)
                            }
                        }

                        IpcMessages.TYPE_BROADCAST,
                        IpcMessages.TYPE_RESPONSE,
                        IpcMessages.TYPE_DISCOVERY_RESPONSE,
                        -> Unit

                        else -> {
                            val requestId = message.string("requestId") ?: continue
                            connection.writeMessage(
                                IpcMessages.errorResponse(
                                    requestId,
                                    IpcConstants.ERROR_NO_HANDLER_FOR_REQUEST,
                                ),
                            )
                        }
                    }
                }
            }
        } finally {
            synchronized(lifecycleLock) {
                if (activeConnection === connection) {
                    activeConnection = null
                    isInitialized = false
                }
            }
        }
    }

    override fun close() {
        val connection = synchronized(lifecycleLock) {
            closed.set(true)
            isInitialized = false
            activeConnection.also { activeConnection = null }
        }
        connection?.close()
    }

    private suspend fun awaitInitializeResponse(
        connection: IpcConnection,
        initializeId: String,
    ): String = try {
        withTimeout(IpcConstants.REQUEST_TIMEOUT_MS) {
            while (true) {
                val message = connection.readMessage()
                if (
                    message.string("type") == IpcMessages.TYPE_RESPONSE &&
                    message.string("requestId") == initializeId
                ) {
                    if (message.string("resultType") != "success") {
                        throw IOException("Router 拒绝 provider 初始化")
                    }
                    return@withTimeout message.obj("result")?.string("clientId")
                        ?: throw IOException("Router 初始化响应缺少 clientId")
                }
            }
            @Suppress("UNREACHABLE_CODE")
            throw IOException("Router 初始化未完成")
        }
    } catch (exception: TimeoutCancellationException) {
        throw IOException("Router 初始化超时", exception)
    }
}
