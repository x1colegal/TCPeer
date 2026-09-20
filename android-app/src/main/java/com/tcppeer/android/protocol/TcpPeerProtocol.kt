package com.tcppeer.android.protocol

import java.io.BufferedInputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets

internal const val CONTROL_PROTOCOL = "TPCP/2"
private const val MAX_CONTROL_SIZE = 16_384
private const val MAX_PACKET_SIZE = 65_535

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
            append(CONTROL_PROTOCOL).append(' ').append(command).append("\r\n")
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
        if (header?.size != 2 || header[0] != CONTROL_PROTOCOL) throw ProtocolException("Invalid control header")
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

    private class ChecksumAccumulator {
        private var sum = 0L
        private var odd = false
        private var high = 0

        fun add(data: ByteArray, offset: Int = 0, length: Int = data.size - offset) {
            var p = offset
            val end = offset + length

            if (odd && p < end) {
                sum += ((high shl 8) or (data[p].toInt() and 0xff)).toLong()
                sum = (sum and 0xffff) + (sum ushr 16)
                odd = false
                p++
            }

            while (p + 1 < end) {
                sum += (
                    ((data[p].toInt() and 0xff) shl 8) or
                    (data[p + 1].toInt() and 0xff)
                ).toLong()

                sum = (sum and 0xffff) + (sum ushr 16)
                p += 2
            }

            if (p < end) {
                high = data[p].toInt() and 0xff
                odd = true
            }
        }

        fun addByte(value: Int) {
            val v = value and 0xff

            if (odd) {
                sum += ((high shl 8) or v).toLong()
                sum = (sum and 0xffff) + (sum ushr 16)
                odd = false
            } else {
                high = v
                odd = true
            }
        }

        fun add16(value: Int) {
            addByte(value ushr 8)
            addByte(value)
        }

        fun add32(value: Int) {
            addByte(value ushr 24)
            addByte(value ushr 16)
            addByte(value ushr 8)
            addByte(value)
        }

        fun finish(): Int {
            if (odd) {
                sum += (high shl 8).toLong()
                odd = false
            }

            while ((sum ushr 16) != 0L)
                sum = (sum and 0xffff) + (sum ushr 16)

            return sum.inv().toInt() and 0xffff
        }
    }

    private fun checksum(
        data: ByteArray,
        offset: Int = 0,
        length: Int = data.size - offset
    ): Int {
        val c = ChecksumAccumulator()
        c.add(data, offset, length)
        return c.finish()
    }

    private fun u16(b: ByteArray, p: Int) =
        ((b[p].toInt() and 0xff) shl 8) or (b[p + 1].toInt() and 0xff)

    private fun u32(b: ByteArray, p: Int): Long =
        ((b[p].toLong() and 0xff) shl 24) or
        ((b[p + 1].toLong() and 0xff) shl 16) or
        ((b[p + 2].toLong() and 0xff) shl 8) or
        (b[p + 3].toLong() and 0xff)

    private fun put16(b: ByteArray, p: Int, v: Int) {
        b[p] = (v ushr 8).toByte()
        b[p + 1] = v.toByte()
    }

    private fun put32(b: ByteArray, p: Int, v: Long) {
        b[p] = (v ushr 24).toByte()
        b[p + 1] = (v ushr 16).toByte()
        b[p + 2] = (v ushr 8).toByte()
        b[p + 3] = v.toByte()
    }

    private fun hex(data: ByteArray) =
        data.joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun unhex(text: String): ByteArray {
        if (text.length % 2 != 0) throw ProtocolException("Invalid hex field")
        return ByteArray(text.length / 2) {
            text.substring(it * 2, it * 2 + 2).toInt(16).toByte()
        }
    }

    private fun tcpFlags(v: Int): String {
        val names = listOf(
            0x100 to "NS", 0x080 to "CWR", 0x040 to "ECE",
            0x020 to "URG", 0x010 to "ACK", 0x008 to "PSH",
            0x004 to "RST", 0x002 to "SYN", 0x001 to "FIN"
        )
        val out = names.filter { (bit, _) -> v and bit != 0 }.map { it.second }
        return if (out.isEmpty()) "NONE" else out.joinToString(",")
    }

    private fun tcpFlagsValue(s: String): Int {
        val values = mapOf(
            "NS" to 0x100, "CWR" to 0x080, "ECE" to 0x040,
            "URG" to 0x020, "ACK" to 0x010, "PSH" to 0x008,
            "RST" to 0x004, "SYN" to 0x002, "FIN" to 0x001
        )
        var out = 0
        s.split(",").forEach {
            val n = it.trim().uppercase()
            if (n.isNotEmpty() && n != "NONE")
                out = out or (values[n] ?: throw ProtocolException("Invalid TCP flag"))
        }
        return out
    }

    private val HEX = "0123456789abcdef".toCharArray()

    private fun hexRange(
        data: ByteArray,
        offset: Int,
        length: Int
    ): String {
        if (length == 0) return ""

        val chars = CharArray(length * 2)
        var src = offset
        var dst = 0
        val end = offset + length

        while (src < end) {
            val v = data[src].toInt() and 0xff
            chars[dst++] = HEX[v ushr 4]
            chars[dst++] = HEX[v and 15]
            src++
        }

        return String(chars)
    }

    private fun ipv4String(data: ByteArray, offset: Int): String =
        buildString(15) {
            append(data[offset].toInt() and 0xff)
            append('.')
            append(data[offset + 1].toInt() and 0xff)
            append('.')
            append(data[offset + 2].toInt() and 0xff)
            append('.')
            append(data[offset + 3].toInt() and 0xff)
        }

    private fun ipv6String(data: ByteArray, offset: Int): String {
        // Mantém representação IPv6 ASCII válida sem criar ByteArray
        // intermediário. Não precisa estar comprimida para o TCPD.
        return buildString(39) {
            var i = 0
            while (i < 8) {
                if (i != 0) append(':')
                append(
                    (
                        ((data[offset + i * 2].toInt() and 0xff) shl 8) or
                        (data[offset + i * 2 + 1].toInt() and 0xff)
                    ).toString(16)
                )
                i++
            }
        }
    }

    private fun appendFlags(out: StringBuilder, flags: Int) {
        var first = true

        fun flag(bit: Int, name: String) {
            if (flags and bit != 0) {
                if (!first) out.append(',')
                out.append(name)
                first = false
            }
        }

        flag(0x100, "NS")
        flag(0x080, "CWR")
        flag(0x040, "ECE")
        flag(0x020, "URG")
        flag(0x010, "ACK")
        flag(0x008, "PSH")
        flag(0x004, "RST")
        flag(0x002, "SYN")
        flag(0x001, "FIN")

        if (first)
            out.append("NONE")
    }


    fun writeData(
        output: OutputStream,
        packet: ByteArray,
        offset: Int = 0,
        length: Int = packet.size
    ) {
        require(
            offset >= 0 &&
            length > 0 &&
            offset + length <= packet.size &&
            length <= MAX_PACKET_SIZE
        )

        val version = (packet[offset].toInt() ushr 4) and 0x0f

        if (version != 4 && version != 6)
            throw ProtocolException(
                "DATA is neither IPv4 nor IPv6"
            )

        val wireLength = when (version) {
            4 -> {
                if (length < 20) throw ProtocolException("Truncated IPv4 header")
                val ihl = (packet[offset].toInt() and 0x0f) * 4
                val declared =
                    ((packet[offset + 2].toInt() and 0xff) shl 8) or
                    (packet[offset + 3].toInt() and 0xff)
                if (ihl < 20 || declared < ihl) throw ProtocolException("Invalid IPv4 packet length")
                declared
            }
            else -> {
                if (length < 40) throw ProtocolException("Truncated IPv6 header")
                40 + (((packet[offset + 4].toInt() and 0xff) shl 8) or
                    (packet[offset + 5].toInt() and 0xff))
            }
        }
        if (wireLength > length) throw ProtocolException("Truncated IP packet")

        output.write(packet, offset, wireLength)
    }

    fun writeDataPlaneControl(output: OutputStream, command: String) {
        require(command == "KEEPALIVE" || command == "PONG")
        output.write("TPCP/2 $command\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
        output.flush()
    }

    fun readData(input: InputStream, output: OutputStream? = null, activity: (() -> Unit)? = null): ByteArray {
        while (true) {
            val first = input.read()
            if (first < 0) throw EOFException("Connection closed while reading IP version or TPCP")
            when ((first ushr 4) and 0x0f) {
                4 -> {
                    val packet = ByteArray(20)
                    packet[0] = first.toByte()
                    input.readIntoExactly(packet, 1, 19)
                    val ihl = (packet[0].toInt() and 0x0f) * 4
                    val totalLength = ((packet[2].toInt() and 0xff) shl 8) or
                        (packet[3].toInt() and 0xff)
                    if (ihl !in 20..60 || totalLength < ihl || totalLength > MAX_PACKET_SIZE)
                        throw ProtocolException("Invalid IPv4 packet length")
                    val complete = packet.copyOf(totalLength)
                    input.readIntoExactly(complete, 20, totalLength - 20)
                    activity?.invoke()
                    return complete
                }
                6 -> {
                    val packet = ByteArray(40)
                    packet[0] = first.toByte()
                    input.readIntoExactly(packet, 1, 39)
                    val payloadLength = ((packet[4].toInt() and 0xff) shl 8) or
                        (packet[5].toInt() and 0xff)
                    val totalLength = 40 + payloadLength
                    if (totalLength > MAX_PACKET_SIZE)
                        throw ProtocolException("IPv6 packet exceeds maximum size")
                    val complete = packet.copyOf(totalLength)
                    input.readIntoExactly(complete, 40, payloadLength)
                    activity?.invoke()
                    return complete
                }
                else -> {
                    if (first != 'T'.code) throw ProtocolException(
                        "Invalid raw IP/TPCP stream prefix: 0x${first.toString(16).padStart(2, '0')}"
                    )
                    when (input.readDataPlaneTpcp(first)) {
                        DIRECT_KEEPALIVE -> {
                            output?.let { synchronized(it) { writeDataPlaneControl(it, "PONG") } }
                            activity?.invoke()
                            continue
                        }
                        DIRECT_PONG -> {
                            activity?.invoke()
                            continue
                        }
                        else -> throw ProtocolException("Invalid data-plane TPCP message")
                    }
                }
            }
        }
    }
}

private const val DIRECT_KEEPALIVE = -1
private const val DIRECT_PONG = -2
private val TPCP_KEEPALIVE_HEADER = "TPCP/2 KEEPALIVE\r\n\r\n".toByteArray(StandardCharsets.US_ASCII)
private val TPCP_PONG_HEADER = "TPCP/2 PONG\r\n\r\n".toByteArray(StandardCharsets.US_ASCII)
private val directHeaderBuffer = object : ThreadLocal<ByteArray>() {
    override fun initialValue() = ByteArray(256)
}

/** Parse a TPCP message after the leading ASCII 'T' has already been read. */
private fun InputStream.readDataPlaneTpcp(first: Int): Int {
    val bytes = directHeaderBuffer.get()!!
    bytes[0] = first.toByte()
    var size = 1
    var matched = 0
    while (matched < 4) {
        val value = read()
        if (value < 0) throw EOFException("Connection closed inside data-plane TPCP")
        if (value > 127) throw ProtocolException("Data-plane TPCP is not ASCII")
        if (size >= bytes.size) throw ProtocolException("Data-plane TPCP message is too large")
        bytes[size++] = value.toByte()
        matched = when {
            (matched == 0 || matched == 2) && value == 13 -> matched + 1
            (matched == 1 || matched == 3) && value == 10 -> matched + 1
            value == 13 -> 1
            else -> 0
        }
    }
    if (bytes.regionMatches(size, TPCP_KEEPALIVE_HEADER)) return DIRECT_KEEPALIVE
    if (bytes.regionMatches(size, TPCP_PONG_HEADER)) return DIRECT_PONG
    throw ProtocolException("Invalid data-plane TPCP message")
}

private fun ByteArray.regionMatches(length: Int, expected: ByteArray): Boolean {
    if (length != expected.size) return false
    for (index in expected.indices) if (this[index] != expected[index]) return false
    return true
}

private fun InputStream.readIntoExactly(destination: ByteArray, offset: Int, length: Int) {
    var written = 0
    while (written < length) {
        val count = read(destination, offset + written, length - written)
        if (count < 0) throw EOFException("Connection closed inside raw IP packet")
        written += count
    }
}
