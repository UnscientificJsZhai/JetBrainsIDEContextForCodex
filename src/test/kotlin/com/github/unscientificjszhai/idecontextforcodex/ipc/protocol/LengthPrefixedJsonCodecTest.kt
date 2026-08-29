package com.github.unscientificjszhai.idecontextforcodex.ipc.protocol

import com.google.gson.JsonObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

class LengthPrefixedJsonCodecTest {
    private val codec = LengthPrefixedJsonCodec()

    @Test
    fun `逐字节 partial read 可以还原帧`() {
        val channel = MemoryChannel(frame("""{"message":"你好"}"""), maxReadBytes = 1)

        val result = runSuspend { codec.readFrame(channel) }

        assertEquals("你好", result["message"].asString)
    }

    @Test
    fun `帧头 EOF 会明确失败`() {
        val channel = MemoryChannel(byteArrayOf(2, 0))

        assertThrows(EOFException::class.java) {
            runSuspend { codec.readFrame(channel) }
        }
    }

    @Test
    fun `帧载荷 EOF 会明确失败`() {
        val channel = MemoryChannel(header(2) + byteArrayOf('{'.code.toByte()))

        assertThrows(EOFException::class.java) {
            runSuspend { codec.readFrame(channel) }
        }
    }

    @Test
    fun `零长度帧会在分配载荷前拒绝`() {
        val channel = MemoryChannel(header(0))

        assertThrows(FrameProtocolException::class.java) {
            runSuspend { codec.readFrame(channel) }
        }
        assertEquals(4, channel.readBytes)
    }

    @Test
    fun `连续多帧可以依次读取`() {
        val channel = MemoryChannel(frame("""{"index":1}""") + frame("""{"index":2}"""), maxReadBytes = 2)

        val first = runSuspend { codec.readFrame(channel) }
        val second = runSuspend { codec.readFrame(channel) }

        assertEquals(1, first["index"].asInt)
        assertEquals(2, second["index"].asInt)
    }

    @Test
    fun `非法 UTF-8 会被严格拒绝`() {
        val invalidUtf8 = byteArrayOf(0xC3.toByte(), 0x28)
        val channel = MemoryChannel(header(invalidUtf8.size) + invalidUtf8)

        assertThrows(FrameProtocolException::class.java) {
            runSuspend { codec.readFrame(channel) }
        }
    }

    @Test
    fun `非法 JSON 会被拒绝`() {
        val channel = MemoryChannel(frame("{"))

        assertThrows(FrameProtocolException::class.java) {
            runSuspend { codec.readFrame(channel) }
        }
    }

    @Test
    fun `宽松 JSON 语法会被严格拒绝`() {
        val nonStandardDocuments = listOf(
            """{/* 注释 */"a":1}""",
            """{'a':1}""",
            """{a:1}""",
            """{"a":1,}""",
            """{"a":1}{"b":2}""",
        )

        nonStandardDocuments.forEach { document ->
            assertThrows("应拒绝：$document", FrameProtocolException::class.java) {
                runSuspend { codec.readFrame(MemoryChannel(frame(document))) }
            }
        }
    }

    @Test
    fun `非对象 JSON 会被拒绝`() {
        val channel = MemoryChannel(frame("[]"))

        assertThrows(FrameProtocolException::class.java) {
            runSuspend { codec.readFrame(channel) }
        }
    }

    @Test
    fun `注入上限的边界长度可以读取且无需大内存`() {
        val boundaryCodec = LengthPrefixedJsonCodec(maxFrameBytes = 2)
        val channel = MemoryChannel(frame("{}"))

        val result = runSuspend { boundaryCodec.readFrame(channel) }

        assertEquals(JsonObject(), result)
    }

    @Test
    fun `超过注入上限会在分配载荷前拒绝`() {
        val boundaryCodec = LengthPrefixedJsonCodec(maxFrameBytes = 2)
        val channel = MemoryChannel(header(3))

        assertThrows(FrameProtocolException::class.java) {
            runSuspend { boundaryCodec.readFrame(channel) }
        }
        assertEquals(4, channel.readBytes)
    }

    @Test
    fun `协议上限加一会在分配载荷前拒绝`() {
        val oversized = IpcConstants.MAX_FRAME_BYTES.toLong() + 1
        val channel = MemoryChannel(header(oversized))

        assertThrows(FrameProtocolException::class.java) {
            runSuspend { codec.readFrame(channel) }
        }
        assertEquals(4, channel.readBytes)
    }

    @Test
    fun `u32 最大值按无符号长度拒绝`() {
        val channel = MemoryChannel(header(0xFFFF_FFFFL))

        assertThrows(FrameProtocolException::class.java) {
            runSuspend { codec.readFrame(channel) }
        }
        assertEquals(4, channel.readBytes)
    }

    @Test
    fun `partial write 会循环写完帧`() {
        val channel = MemoryChannel(maxWriteBytes = 1)
        val message = JsonObject().apply { addProperty("message", "你好") }

        runSuspend { codec.writeFrame(channel, message) }

        val bytes = channel.writtenBytes()
        val payloadLength = ByteBuffer.wrap(bytes, 0, 4)
            .order(ByteOrder.LITTLE_ENDIAN)
            .int
        val payload = bytes.copyOfRange(4, bytes.size)
        assertEquals(payload.size, payloadLength)
        assertEquals("""{"message":"你好"}""", payload.toString(StandardCharsets.UTF_8))
    }

    @Test
    fun `写入恰好位于注入上限的对象`() {
        val boundaryCodec = LengthPrefixedJsonCodec(maxFrameBytes = 2)
        val channel = MemoryChannel(maxWriteBytes = 1)

        runSuspend { boundaryCodec.writeFrame(channel, JsonObject()) }

        assertArrayEquals(frame("{}"), channel.writtenBytes())
    }

    @Test
    fun `写入超过注入上限时不会写出帧头`() {
        val boundaryCodec = LengthPrefixedJsonCodec(maxFrameBytes = 2)
        val channel = MemoryChannel()
        val message = JsonObject().apply { addProperty("a", 1) }

        assertThrows(FrameProtocolException::class.java) {
            runSuspend { boundaryCodec.writeFrame(channel, message) }
        }
        assertEquals(0, channel.writtenBytes().size)
    }

    @Test
    fun `读取返回零不会形成忙循环`() {
        val channel = MemoryChannel(frame("{}"), zeroReadOnce = true)

        assertThrows(IOException::class.java) {
            runSuspend { codec.readFrame(channel) }
        }
    }

    @Test
    fun `写入返回零不会形成忙循环`() {
        val channel = MemoryChannel(zeroWriteOnce = true)

        assertThrows(IOException::class.java) {
            runSuspend { codec.writeFrame(channel, JsonObject()) }
        }
    }

    private class MemoryChannel(
        private val input: ByteArray = byteArrayOf(),
        private val maxReadBytes: Int = Int.MAX_VALUE,
        private val maxWriteBytes: Int = Int.MAX_VALUE,
        private var zeroReadOnce: Boolean = false,
        private var zeroWriteOnce: Boolean = false,
    ) : SuspendByteChannel {
        private var readOffset = 0
        private val output = ByteArrayOutputStream()

        val readBytes: Int
            get() = readOffset

        override suspend fun read(destination: ByteBuffer): Int {
            if (zeroReadOnce) {
                zeroReadOnce = false
                return 0
            }
            if (readOffset == input.size) {
                return -1
            }

            val count = minOf(destination.remaining(), maxReadBytes, input.size - readOffset)
            destination.put(input, readOffset, count)
            readOffset += count
            return count
        }

        override suspend fun write(source: ByteBuffer): Int {
            if (zeroWriteOnce) {
                zeroWriteOnce = false
                return 0
            }

            val count = minOf(source.remaining(), maxWriteBytes)
            val bytes = ByteArray(count)
            source.get(bytes)
            output.write(bytes)
            return count
        }

        fun writtenBytes(): ByteArray = output.toByteArray()
    }

    private companion object {
        fun frame(json: String): ByteArray {
            val payload = json.toByteArray(StandardCharsets.UTF_8)
            return header(payload.size.toLong()) + payload
        }

        fun header(length: Int): ByteArray = header(length.toLong())

        fun header(length: Long): ByteArray = ByteBuffer.allocate(4)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(length.toInt())
            .array()

        fun <T> runSuspend(block: suspend () -> T): T {
            var outcome: Result<T>? = null
            block.startCoroutine(object : Continuation<T> {
                override val context = EmptyCoroutineContext

                override fun resumeWith(result: Result<T>) {
                    outcome = result
                }
            })
            return checkNotNull(outcome) { "测试挂起函数未同步完成" }.getOrThrow()
        }
    }
}
