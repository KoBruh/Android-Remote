package com.androidremote.app.tv

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Message framing of the Android TV Remote protocol v2: every protobuf message is prefixed
 * with its length as a base-128 varint.
 *
 * Ported from androidtvremote2 (base.py, ProtobufProtocol).
 */
object Framing {

    /** Largest message we accept. Real messages are far smaller; this guards against a corrupt length. */
    const val MAX_MESSAGE_SIZE = 1 shl 20

    /** Encodes [value] (must be >= 0) as a varint. */
    fun encodeVarint(value: Int): ByteArray {
        require(value >= 0) { "Negative length" }
        val out = ByteArrayOutputStream(5)
        var rest = value
        while (rest >= 0x80) {
            out.write((rest and 0x7F) or 0x80)
            rest = rest ushr 7
        }
        out.write(rest)
        return out.toByteArray()
    }

    /** Returns [payload] with its varint length prefix. */
    fun frame(payload: ByteArray): ByteArray = encodeVarint(payload.size) + payload

    /** Writes one framed message and flushes. */
    @Throws(IOException::class)
    fun write(out: OutputStream, payload: ByteArray) {
        out.write(frame(payload))
        out.flush()
    }

    /**
     * Blocks until one complete message has been read and returns its payload.
     *
     * @return null if the stream ended cleanly before the next message started.
     * @throws EOFException if the stream ended in the middle of a message.
     * @throws IOException if the length is corrupt or larger than [MAX_MESSAGE_SIZE].
     */
    @Throws(IOException::class)
    fun read(input: InputStream): ByteArray? {
        var length = 0
        var shift = 0
        while (true) {
            val b = input.read()
            if (b < 0) {
                if (shift == 0) return null
                throw EOFException("Stream ended inside a message length")
            }
            if (shift >= 32) throw IOException("Corrupt message length")
            length = length or ((b and 0x7F) shl shift)
            if (b and 0x80 == 0) break
            shift += 7
        }
        if (length < 0 || length > MAX_MESSAGE_SIZE) throw IOException("Message too large: $length")
        val payload = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(payload, read, length - read)
            if (n < 0) throw EOFException("Stream ended inside a message")
            read += n
        }
        return payload
    }
}
