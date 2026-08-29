package com.github.unscientificjszhai.idecontextforcodex.ipc.router

import com.github.unscientificjszhai.idecontextforcodex.ipc.protocol.IpcConstants
import com.github.unscientificjszhai.idecontextforcodex.ipc.protocol.IpcMessages
import com.github.unscientificjszhai.idecontextforcodex.ipc.protocol.int
import com.github.unscientificjszhai.idecontextforcodex.ipc.protocol.string
import com.github.unscientificjszhai.idecontextforcodex.ipc.transport.IpcConnection
import com.github.unscientificjszhai.idecontextforcodex.ipc.transport.IpcTransport
import com.google.gson.JsonObject
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.EOFException
import java.io.IOException
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * 当没有兼容 Router 时，由当前 JetBrains 进程提供的最小 IPC Router。
 *
 * Router 不读取或缓存 IDE 内容，只负责 provider 注册、能力发现与请求/响应转发。
 */
class JetBrainsIpcRouter(
    private val transport: IpcTransport,
) : AutoCloseable {
    private val clients = CopyOnWriteArrayList<RouterClientState>()
    private val clientSequence = AtomicLong()

    val isRunning: Boolean
        get() = transport.isRunning

    internal val registeredClientCount: Int
        get() = clients.size

    suspend fun start() {
        transport.start(::handleConnection)
    }

    private suspend fun handleConnection(connection: IpcConnection) {
        val state = RouterClientState(connection)
        try {
            while (connection.isOpen) {
                val message = if (state.clientId == null) {
                    withTimeout(IpcConstants.REQUEST_TIMEOUT_MS) {
                        connection.readMessage()
                    }
                } else {
                    connection.readMessage()
                }
                val isInitialize = message.string("type") == IpcMessages.TYPE_REQUEST &&
                        message.string("method") == IpcMessages.METHOD_INITIALIZE
                when (message.string("type")) {
                    IpcMessages.TYPE_REQUEST -> {
                        if (isInitialize) {
                            initializeClient(state, message)
                        } else {
                            routeRequest(state, message)
                        }
                    }

                    IpcMessages.TYPE_RESPONSE,
                    IpcMessages.TYPE_DISCOVERY_RESPONSE,
                        -> state.completePending(message)

                    IpcMessages.TYPE_BROADCAST -> Unit
                    else -> {
                        message.string("requestId")?.let { requestId ->
                            connection.writeMessage(
                                IpcMessages.errorResponse(
                                    requestId,
                                    IpcConstants.ERROR_NO_HANDLER_FOR_REQUEST,
                                ),
                            )
                        }
                    }
                }
                // Codex TUI 使用一次性请求连接；只有成功 initialize 的 provider 保持长连接。
                if (state.clientId == null && !isInitialize) return
            }
        } catch (_: EOFException) {
            // 正常断连，由 finally 清理注册和 pending 请求。
        } finally {
            clients -= state
            state.disconnect()
        }
    }

    private suspend fun initializeClient(state: RouterClientState, message: JsonObject) {
        val requestId = message.string("requestId") ?: return
        if (state.clientId != null) {
            state.connection.writeMessage(
                IpcMessages.errorResponse(requestId, IpcConstants.ERROR_NO_HANDLER_FOR_REQUEST),
            )
            return
        }
        if (message.int("version") != IpcConstants.PROTOCOL_VERSION) {
            state.connection.writeMessage(
                IpcMessages.errorResponse(requestId, IpcConstants.ERROR_REQUEST_VERSION_MISMATCH),
            )
            return
        }

        val clientId = "jetbrains-provider-${clientSequence.incrementAndGet()}"
        state.connection.writeMessage(IpcMessages.initializeSuccess(requestId, clientId))
        state.clientId = clientId
        clients += state
    }

    private suspend fun routeRequest(requester: RouterClientState, request: JsonObject) {
        val requestId = request.string("requestId") ?: return
        if (request.int("version") != IpcConstants.PROTOCOL_VERSION) {
            requester.connection.writeMessage(
                IpcMessages.errorResponse(requestId, IpcConstants.ERROR_REQUEST_VERSION_MISMATCH),
            )
            return
        }
        if (request.string("method") != IpcConstants.IDE_CONTEXT_METHOD) {
            requester.connection.writeMessage(
                IpcMessages.errorResponse(requestId, IpcConstants.ERROR_NO_HANDLER_FOR_REQUEST),
            )
            return
        }

        try {
            withTimeout(IpcConstants.REQUEST_TIMEOUT_MS) {
                val provider = discoverProvider(request)
                if (provider == null) {
                    requester.connection.writeMessage(
                        IpcMessages.errorResponse(requestId, IpcConstants.ERROR_NO_CLIENT_FOUND),
                    )
                    return@withTimeout
                }

                val response = try {
                    provider.request(requestId, request)
                } catch (_: IOException) {
                    IpcMessages.errorResponse(requestId, IpcConstants.ERROR_CLIENT_DISCONNECTED)
                }
                requester.connection.writeMessage(response)
            }
        } catch (_: TimeoutCancellationException) {
            if (requester.connection.isOpen) {
                requester.connection.writeMessage(
                    IpcMessages.errorResponse(requestId, IpcConstants.ERROR_REQUEST_TIMEOUT),
                )
            }
        }
    }

    private suspend fun discoverProvider(request: JsonObject): RouterClientState? = supervisorScope {
        val matches = clients
            .toList()
            .filter { it.connection.isOpen }
            .map { candidate ->
                async {
                    val discoveryId = UUID.randomUUID().toString()
                    try {
                        val response = withTimeout(DISCOVERY_CANDIDATE_TIMEOUT_MS) {
                            candidate.request(
                                discoveryId,
                                IpcMessages.discoveryRequest(discoveryId, request),
                            )
                        }
                        val canHandle = response
                            .get("response")
                            ?.takeIf { it.isJsonObject }
                            ?.asJsonObject
                            ?.get("canHandle")
                            ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }
                            ?.asBoolean
                            ?: false
                        candidate.takeIf { canHandle }
                    } catch (_: TimeoutCancellationException) {
                        currentCoroutineContext().ensureActive()
                        null
                    } catch (_: IOException) {
                        null
                    }
                }
            }
            .awaitAll()
            .filterNotNull()

        // v0 discovery 只返回 canHandle，没有可证实的 root 排名字段；多匹配时安全失败。
        matches.singleOrNull()
    }

    override fun close() {
        clients.toList().forEach(RouterClientState::disconnect)
        clients.clear()
        transport.close()
    }

    private companion object {
        const val DISCOVERY_CANDIDATE_TIMEOUT_MS = 1_000L
    }
}

private class RouterClientState(
    val connection: IpcConnection,
) {
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JsonObject>>()
    private val requestMutex = Mutex()

    @Volatile
    var clientId: String? = null

    suspend fun request(requestId: String, message: JsonObject): JsonObject =
        requestMutex.withLock {
            val deferred = CompletableDeferred<JsonObject>()
            if (pending.putIfAbsent(requestId, deferred) != null) {
                throw IOException("Router 请求 id 冲突")
            }
            try {
                connection.writeMessage(message)
                deferred.await()
            } finally {
                pending.remove(requestId, deferred)
            }
        }

    fun completePending(message: JsonObject) {
        val requestId = message.string("requestId") ?: return
        pending.remove(requestId)?.complete(message)
    }

    fun disconnect() {
        val exception = EOFException("Router client 已断开")
        pending.values.forEach { it.completeExceptionally(exception) }
        pending.clear()
        connection.close()
    }
}
