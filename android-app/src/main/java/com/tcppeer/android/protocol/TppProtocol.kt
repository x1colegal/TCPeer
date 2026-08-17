package com.tcppeer.android.protocol

import java.net.Inet6Address
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class TppMessage(
    val type: Int,
    val identifier: Long,
    val timestampNanos: Long,
    val source: Inet6Address,
    val destination: Inet6Address,
)

object TppProtocol {
    const val NEXT_HEADER = 99
    const val ECHO_REQUEST = 1
    const val ECHO_REPLY = 2
    private const val VERSION = 1
    private const val PAYLOAD_SIZE = 24
    private val magic = byteArrayOf('T'.code.toByte(), 'P'.code.toByte(), 'P'.code.toByte(), '1'.code.toByte())

    fun request(
        source: Inet6Address,
        destination: Inet6Address,
        identifier: Long,
        timestampNanos: Long,
    ): ByteArray = build(source, destination, ECHO_REQUEST, identifier, timestampNanos)

    fun parse(packet: ByteArray): TppMessage? {
        if (
            packet.size < 40 + PAYLOAD_SIZE || (packet[0].toInt() ushr 4) != 6 ||
            (packet[6].toInt() and 0xFF) != NEXT_HEADER
        ) return null
        val buffer = ByteBuffer.wrap(packet).order(ByteOrder.BIG_ENDIAN)
        val payloadLength = buffer.getShort(4).toInt() and 0xFFFF
        if (payloadLength != PAYLOAD_SIZE || packet.size < 40 + payloadLength) return null
        val source = InetAddress.getByAddress(packet.copyOfRange(8, 24)) as Inet6Address
        val destination = InetAddress.getByAddress(packet.copyOfRange(24, 40)) as Inet6Address
        if (!packet.copyOfRange(40, 44).contentEquals(magic)) return null
        val version = packet[44].toInt() and 0xFF
        val type = packet[45].toInt() and 0xFF
        if (version != VERSION || type !in setOf(ECHO_REQUEST, ECHO_REPLY)) return null
        return TppMessage(type, buffer.getLong(48), buffer.getLong(56), source, destination)
    }

    private fun build(
        source: Inet6Address,
        destination: Inet6Address,
        type: Int,
        identifier: Long,
        timestampNanos: Long,
    ): ByteArray = ByteBuffer.allocate(40 + PAYLOAD_SIZE).order(ByteOrder.BIG_ENDIAN).apply {
        putInt(6 shl 28)
        putShort(PAYLOAD_SIZE.toShort())
        put(NEXT_HEADER.toByte())
        put(64)
        put(source.address)
        put(destination.address)
        put(magic)
        put(VERSION.toByte())
        put(type.toByte())
        putShort(0)
        putLong(identifier)
        putLong(timestampNanos)
    }.array()
}
