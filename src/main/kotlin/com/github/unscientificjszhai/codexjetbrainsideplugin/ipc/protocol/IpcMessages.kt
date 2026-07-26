package com.github.unscientificjszhai.codexjetbrainsideplugin.ipc.protocol

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.util.UUID

internal object IpcMessages {
    const val TYPE_REQUEST = "request"
    const val TYPE_RESPONSE = "response"
    const val TYPE_BROADCAST = "broadcast"
    const val TYPE_DISCOVERY_REQUEST = "client-discovery-request"
    const val TYPE_DISCOVERY_RESPONSE = "client-discovery-response"

    const val METHOD_INITIALIZE = "initialize"

    fun initializeRequest(requestId: String = UUID.randomUUID().toString()): JsonObject = JsonObject().apply {
        addProperty("type", TYPE_REQUEST)
        addProperty("requestId", requestId)
        addProperty("sourceClientId", "initializing-client")
        addProperty("version", IpcConstants.PROTOCOL_VERSION)
        addProperty("method", METHOD_INITIALIZE)
        add(
            "params",
            JsonObject().apply {
                // 目标协议当前只确认该枚举值。
                addProperty("clientType", "vscode")
            },
        )
    }

    fun initializeSuccess(requestId: String, clientId: String): JsonObject = successResponse(
        requestId = requestId,
        method = METHOD_INITIALIZE,
        handledByClientId = clientId,
        result = JsonObject().apply { addProperty("clientId", clientId) },
    )

    fun discoveryRequest(
        requestId: String,
        originalRequest: JsonObject,
    ): JsonObject = JsonObject().apply {
        addProperty("type", TYPE_DISCOVERY_REQUEST)
        addProperty("requestId", requestId)
        add("request", originalRequest.deepCopy())
    }

    fun discoveryResponse(requestId: String, canHandle: Boolean): JsonObject = JsonObject().apply {
        addProperty("type", TYPE_DISCOVERY_RESPONSE)
        addProperty("requestId", requestId)
        add(
            "response",
            JsonObject().apply {
                addProperty("canHandle", canHandle)
            },
        )
    }

    fun successResponse(
        requestId: String,
        method: String,
        handledByClientId: String,
        result: JsonElement,
    ): JsonObject = JsonObject().apply {
        addProperty("type", TYPE_RESPONSE)
        addProperty("requestId", requestId)
        addProperty("resultType", "success")
        addProperty("method", method)
        addProperty("handledByClientId", handledByClientId)
        add("result", result)
    }

    fun errorResponse(requestId: String, error: String): JsonObject = JsonObject().apply {
        addProperty("type", TYPE_RESPONSE)
        addProperty("requestId", requestId)
        addProperty("resultType", "error")
        addProperty("error", error)
    }
}

internal fun JsonObject.string(name: String): String? =
    get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

internal fun JsonObject.int(name: String): Int? =
    runCatching {
        get(name)
            ?.takeIf(JsonElement::isJsonPrimitive)
            ?.asJsonPrimitive
            ?.takeIf { it.isNumber }
            ?.asInt
    }.getOrNull()

internal fun JsonObject.obj(name: String): JsonObject? =
    get(name)?.takeIf(JsonElement::isJsonObject)?.asJsonObject
