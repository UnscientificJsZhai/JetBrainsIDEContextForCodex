package com.github.unscientificjszhai.idecontextforcodex.ipc.protocol

import com.github.unscientificjszhai.idecontextforcodex.ipc.IdeContextRequestHandler
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Test

class IpcMessagesFixtureTest {
    @Test
    fun `initialize 请求与静态协议 fixture 结构一致`() {
        val fixture = fixture("initialize-request.json")
        val requestId = fixture.string("requestId")!!

        assertEquals(fixture, IpcMessages.initializeRequest(requestId))
    }

    @Test
    fun `initialize 响应与静态协议 fixture 结构一致`() {
        val fixture = fixture("initialize-response.json")

        assertEquals(
            fixture,
            IpcMessages.initializeSuccess(
                requestId = fixture.string("requestId")!!,
                clientId = fixture.obj("result")!!.string("clientId")!!,
            ),
        )
    }

    @Test
    fun `discovery 请求与静态协议 fixture 结构一致`() {
        val fixture = fixture("client-discovery-request.json")

        assertEquals(
            fixture,
            IpcMessages.discoveryRequest(
                requestId = fixture.string("requestId")!!,
                originalRequest = fixture.obj("request")!!,
            ),
        )
    }

    @Test
    fun `discovery true 响应与静态协议 fixture 结构一致`() {
        val fixture = fixture("client-discovery-response-true.json")

        assertEquals(
            fixture,
            IpcMessages.discoveryResponse(
                requestId = fixture.string("requestId")!!,
                canHandle = true,
            ),
        )
    }

    @Test
    fun `provider 不会声明处理未知版本或未知方法`() = runBlocking {
        val handler = IdeContextRequestHandler()
        val request = fixture("request-v0.json")

        request.addProperty("version", 1)
        assertFalse(handler.canHandle(request))

        request.addProperty("version", IpcConstants.PROTOCOL_VERSION)
        request.addProperty("method", "unknown-method")
        assertFalse(handler.canHandle(request))
    }

    private fun fixture(name: String): JsonObject {
        val stream = checkNotNull(javaClass.getResourceAsStream("/protocol/ide-context/$name"))
        return stream.reader().use { JsonParser.parseReader(it).asJsonObject }
    }
}
