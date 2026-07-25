package com.github.unscientificjszhai.codexjetbrainsideplugin.ipc.protocol

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.EOFException
import java.io.IOException
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * 可由纯 JVM 测试替换的挂起字节通道。
 *
 * 生产适配器必须在暂时没有数据或写入空间时挂起，不能在 EDT 上轮询或阻塞；完成、
 * EOF 或失败时才恢复调用。成功的读写必须推进缓冲区位置并返回正数，读取结束返回 -1。
 */
interface SuspendByteChannel {
    suspend fun read(destination: ByteBuffer): Int

    suspend fun write(source: ByteBuffer): Int
}

class FrameProtocolException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * 编解码 4 字节 little-endian 无符号长度与 UTF-8 JSON 组成的帧。
 *
 * 挂起函数不会自行切换线程；调用方及通道适配器必须保证网络或管道等待不阻塞 EDT。
 */
class LengthPrefixedJsonCodec(
    private val gson: Gson = Gson(),
    private val maxFrameBytes: Long = IpcConstants.MAX_FRAME_BYTES.toLong(),
) {
    init {
        require(maxFrameBytes in 1..IpcConstants.MAX_FRAME_BYTES.toLong()) {
            "帧上限必须位于 1..${IpcConstants.MAX_FRAME_BYTES}"
        }
    }

    suspend fun readFrame(channel: SuspendByteChannel): JsonObject {
        val header = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        readFully(channel, header, "帧头")
        header.flip()

        val payloadLength = Integer.toUnsignedLong(header.int)
        validatePayloadLength(payloadLength)

        val payload = ByteBuffer.allocate(payloadLength.toInt())
        readFully(channel, payload, "帧载荷")
        payload.flip()

        val jsonText = try {
            StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(payload)
                .toString()
        } catch (exception: CharacterCodingException) {
            throw FrameProtocolException("帧载荷不是合法 UTF-8", exception)
        }

        val element = try {
            val reader = JsonReader(StringReader(jsonText)).apply {
                strictness = Strictness.STRICT
            }
            val parsed = JsonParser.parseReader(reader)
            if (reader.peek() != JsonToken.END_DOCUMENT) {
                throw JsonParseException("JSON 文档包含尾随内容")
            }
            parsed
        } catch (exception: JsonParseException) {
            throw FrameProtocolException("帧载荷不是合法 JSON", exception)
        } catch (exception: IOException) {
            throw FrameProtocolException("读取 JSON 帧载荷失败", exception)
        }
        if (!element.isJsonObject) {
            throw FrameProtocolException("帧载荷必须是 JSON 对象")
        }
        return element.asJsonObject
    }

    suspend fun writeFrame(channel: SuspendByteChannel, message: JsonObject) {
        val payload = gson.toJson(message).toByteArray(StandardCharsets.UTF_8)
        validatePayloadLength(payload.size.toLong())

        val header = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        header.putInt(payload.size)
        header.flip()

        writeFully(channel, header, "帧头")
        writeFully(channel, ByteBuffer.wrap(payload), "帧载荷")
    }

    private fun validatePayloadLength(payloadLength: Long) {
        if (payloadLength == 0L) {
            throw FrameProtocolException("帧载荷长度不能为 0")
        }
        if (payloadLength > maxFrameBytes) {
            throw FrameProtocolException("帧载荷长度 $payloadLength 超过上限 $maxFrameBytes")
        }
    }

    private suspend fun readFully(channel: SuspendByteChannel, destination: ByteBuffer, section: String) {
        while (destination.hasRemaining()) {
            when (val count = channel.read(destination)) {
                -1 -> throw EOFException("读取${section}时遇到 EOF")
                0 -> throw IOException("挂起通道读取${section}时未推进缓冲区")
                in 1..Int.MAX_VALUE -> Unit
                else -> throw IOException("挂起通道读取${section}时返回非法计数 $count")
            }
        }
    }

    private suspend fun writeFully(channel: SuspendByteChannel, source: ByteBuffer, section: String) {
        while (source.hasRemaining()) {
            val count = channel.write(source)
            if (count <= 0) {
                throw IOException("挂起通道写入${section}时未推进缓冲区")
            }
        }
    }

    private companion object {
        const val HEADER_BYTES = 4
    }
}
