package com.tcppeer.android.protocol

import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

private const val PROTOCOL = "TCPeer/1.0"
private const val MAX_CONTROL_SIZE = 16_384
private const val MAX_PACKET_SIZE = 65_535
private val DATA_MAGIC = byteArrayOf('T'.code.toByte(), 'C'.code.toByte(), 'P'.code.toByte(), 'D'.code.toByte())

class ProtocolException(message: String) : Exception(message)

data class ControlMessage(
    val command: String,
    val fields: Map<String, String> = emptyMap(),
) {
    fun field(name: String): String? = fields.entries.firstOrNull {
        it.key.equals(name, ignoreCase = true)
    }?.value

    fun encode(): ByteArray {
        require(command.matches(Regex("[A-Z][A-Z0-9-]*"))) { "Invalid control command" }
        val text = buildString {
            append(PROTOCOL).append(' ').append(command).append("\r\n")
            fields.forEach { (name, value) ->
                require(name.isNotEmpty() && ':' !in name && '\r' !in name && '\n' !in name) {
                    "Invalid control field name"
                }
                require('\r' !in value && '\n' !in value) { "Invalid control field value" }
                append(name).append(": ").append(value).append("\r\n")
            }
            append("\r\n")
        }
        require(text.all { it.code in 0..127 }) { "Control messages must contain ASCII only" }
        return text.toByteArray(StandardCharsets.US_ASCII).also {
            require(it.size <= MAX_CONTROL_SIZE) { "Control message is too large" }
        }
    }
}

object TcpPeerProtocol {
    fun writeControl(output: OutputStream, message: ControlMessage) {
        output.write(message.encode())
        output.flush()
    }

    fun readControl(input: InputStream): ControlMessage {
        val bytes = ArrayList<Byte>()
        var matched = 0
        val terminator = byteArrayOf(13, 10, 13, 10)
        while (matched < terminator.size) {
            val value = input.read()
            if (value < 0) throw EOFException("Control connection closed")
            bytes.add(value.toByte())
            if (bytes.size > MAX_CONTROL_SIZE) throw ProtocolException("Control message is too large")
            matched = if (value.toByte() == terminator[matched]) matched + 1 else if (value == 13) 1 else 0
        }
        val text = bytes.toByteArray().toString(StandardCharsets.US_ASCII)
        if (text.any { it.code > 127 }) throw ProtocolException("Control messages must contain ASCII only")
        val lines = text.removeSuffix("\r\n\r\n").split("\r\n")
        val header = lines.firstOrNull()?.split(' ', limit = 2)
        if (header?.size != 2 || header[0] != PROTOCOL) throw ProtocolException("Invalid control header")
        val fields = linkedMapOf<String, String>()
        lines.drop(1).forEach { line ->
            val separator = line.indexOf(": ")
            if (separator <= 0) throw ProtocolException("Invalid control field")
            val name = line.substring(0, separator)
            if (fields.keys.any { it.equals(name, ignoreCase = true) }) {
                throw ProtocolException("Duplicate control field")
            }
            fields[name] = line.substring(separator + 2)
        }
        return ControlMessage(header[1], fields)
    }

    fun writeData(output: OutputStream, packet: ByteArray, offset: Int = 0, length: Int = packet.size) {
        require(offset >= 0 && length > 0 && offset + length <= packet.size && length <= MAX_PACKET_SIZE) {
            "Invalid IP packet size"
        }
        val family = (packet[offset].toInt() ushr 4) and 0x0F
        require(family == 4 || family == 6) { "DATA contains neither IPv4 nor IPv6" }
        // Send one complete frame in one syscall. With TCP_NODELAY, writing the
        // header separately creates a tiny TCP segment for every VPN packet.
        val frame = ByteArray(10 + length)
        DATA_MAGIC.copyInto(frame)
        frame[4] = 1
        frame[5] = family.toByte()
        ByteBuffer.wrap(frame).order(ByteOrder.BIG_ENDIAN).putInt(6, length)
        packet.copyInto(frame, 10, offset, offset + length)
        output.write(frame)
    }

    fun readData(input: InputStream): ByteArray {
        val header = input.readExactly(10)
        if (!header.copyOfRange(0, 4).contentEquals(DATA_MAGIC) || header[4].toInt() != 1) {
            throw ProtocolException("Invalid DATA frame header")
        }
        val family = header[5].toInt() and 0xFF
        val length = ByteBuffer.wrap(header, 6, 4).order(ByteOrder.BIG_ENDIAN).int
        if (family !in setOf(4, 6) || length !in 1..MAX_PACKET_SIZE) {
            throw ProtocolException("Invalid DATA frame metadata")
        }
        val packet = input.readExactly(length)
        if (((packet[0].toInt() ushr 4) and 0x0F) != family) {
            throw ProtocolException("DATA family does not match its packet")
        }
        return packet
    }
}

private fun InputStream.readExactly(size: Int): ByteArray {
    val result = ByteArray(size)
    var offset = 0
    while (offset < size) {
        val count = read(result, offset, size - offset)
        if (count < 0) throw EOFException("Connection closed while reading a frame")
        offset += count
    }
    return result
}
