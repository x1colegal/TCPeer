package com.tcppeer.android.protocol

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import kotlin.random.Random

data class DhcpOffer(
    val transactionId: Int,
    val address: Inet4Address,
    val prefixLength: Int,
    val server: Inet4Address,
    val dns: List<Inet4Address>,
    val leaseSeconds: Long,
)

data class SlaacConfiguration(
    val address: Inet6Address,
    val prefix: Inet6Address,
    val prefixLength: Int,
    val dns: List<Inet6Address>,
)

object AddressNegotiation {
    private const val DHCP_DISCOVER = 1
    private const val DHCP_OFFER = 2
    private const val DHCP_REQUEST = 3
    private const val DHCP_ACK = 5
    private const val DHCP_SERVER_PORT = 67
    private const val DHCP_CLIENT_PORT = 68

    fun transactionId(): Int = Random.nextInt()

    fun dhcpDiscover(peerId: String, transactionId: Int): ByteArray = dhcpPacket(
        messageType = DHCP_DISCOVER,
        peerId = peerId,
        transactionId = transactionId,
        requestedAddress = null,
        serverAddress = null,
    )

    fun dhcpRequest(peerId: String, offer: DhcpOffer): ByteArray = dhcpPacket(
        messageType = DHCP_REQUEST,
        peerId = peerId,
        transactionId = offer.transactionId,
        requestedAddress = offer.address,
        serverAddress = offer.server,
    )

    fun parseDhcpOffer(packet: ByteArray, expectedTransactionId: Int): DhcpOffer? =
        parseDhcp(packet, expectedTransactionId, DHCP_OFFER)

    fun parseDhcpAck(packet: ByteArray, expectedTransactionId: Int): DhcpOffer? =
        parseDhcp(packet, expectedTransactionId, DHCP_ACK)

    private fun dhcpPacket(
        messageType: Int,
        peerId: String,
        transactionId: Int,
        requestedAddress: Inet4Address?,
        serverAddress: Inet4Address?,
    ): ByteArray {
        val clientId = peerId.toByteArray(StandardCharsets.US_ASCII)
        require(clientId.size <= 254) { "Peer ID is too long for DHCP" }
        val hardwareAddress = syntheticHardwareAddress(peerId)
        val payload = ByteBuffer.allocate(576).order(ByteOrder.BIG_ENDIAN)
        payload.put(1).put(1).put(6).put(0)
        payload.putInt(transactionId).putShort(0).putShort(0x8000.toShort())
        repeat(4) { payload.putInt(0) }
        payload.put(hardwareAddress).put(ByteArray(10))
        payload.put(ByteArray(64 + 128))
        payload.put(byteArrayOf(0x63, 0x82.toByte(), 0x53, 0x63))
        option(payload, 53, byteArrayOf(messageType.toByte()))
        option(payload, 61, byteArrayOf(0) + clientId)
        requestedAddress?.let { option(payload, 50, it.address) }
        serverAddress?.let { option(payload, 54, it.address) }
        option(payload, 55, byteArrayOf(1, 3, 6, 51, 54))
        payload.put(255.toByte())
        val dhcp = payload.array().copyOf(payload.position().coerceAtLeast(300))
        return ipv4UdpPacket(
            source = byteArrayOf(0, 0, 0, 0),
            destination = byteArrayOf(-1, -1, -1, -1),
            sourcePort = DHCP_CLIENT_PORT,
            destinationPort = DHCP_SERVER_PORT,
            payload = dhcp,
        )
    }

    private fun parseDhcp(packet: ByteArray, expectedTransactionId: Int, expectedType: Int): DhcpOffer? {
        if (packet.size < 20 + 8 + 240 || packet.version() != 4 || packet[9].toInt() != 17) return null
        val ipHeader = (packet[0].toInt() and 0x0F) * 4
        if (packet.size < ipHeader + 8 + 240) return null
        val udp = ByteBuffer.wrap(packet, ipHeader, 8).order(ByteOrder.BIG_ENDIAN)
        val sourcePort = udp.short.toInt() and 0xFFFF
        val destinationPort = udp.short.toInt() and 0xFFFF
        if (sourcePort != DHCP_SERVER_PORT || destinationPort != DHCP_CLIENT_PORT) return null
        val dhcpOffset = ipHeader + 8
        val dhcp = ByteBuffer.wrap(packet).order(ByteOrder.BIG_ENDIAN)
        if (dhcp.getInt(dhcpOffset + 4) != expectedTransactionId) return null
        val address = inet4(packet.copyOfRange(dhcpOffset + 16, dhcpOffset + 20))
        if (!packet.copyOfRange(dhcpOffset + 236, dhcpOffset + 240).contentEquals(byteArrayOf(0x63, 0x82.toByte(), 0x53, 0x63))) return null
        val options = parseOptions(packet, dhcpOffset + 240)
        if (options[53]?.firstOrNull()?.toInt() != expectedType) return null
        val server = options[54]?.takeIf { it.size == 4 }?.let(::inet4) ?: return null
        val mask = options[1]?.takeIf { it.size == 4 } ?: byteArrayOf(-1, -1, -1, 0)
        val prefixLength = mask.sumOf { Integer.bitCount(it.toInt() and 0xFF) }
        val dns = (options[6] ?: byteArrayOf()).asList().chunked(4)
            .filter { it.size == 4 }
            .map { inet4(it.toByteArray()) }
        val leaseSeconds = options[51]?.takeIf { it.size == 4 }
            ?.let { ByteBuffer.wrap(it).order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xFFFFFFFFL } ?: 0L
        return DhcpOffer(expectedTransactionId, address, prefixLength, server, dns, leaseSeconds)
    }

    fun routerSolicitation(): ByteArray {
        val source = ByteArray(16)
        val destination = InetAddress.getByName("ff02::2").address
        val icmp = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
            .put(133.toByte()).put(0).putShort(0).putInt(0).array()
        val checksum = internetChecksum(source + destination + ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
            .putInt(icmp.size).putInt(58).array() + icmp)
        ByteBuffer.wrap(icmp).order(ByteOrder.BIG_ENDIAN).putShort(2, checksum.toShort())
        return ByteBuffer.allocate(40 + icmp.size).order(ByteOrder.BIG_ENDIAN)
            .putInt(6 shl 28).putShort(icmp.size.toShort()).put(58).put(255.toByte())
            .put(source).put(destination).put(icmp).array()
    }

    fun parseRouterAdvertisement(packet: ByteArray, interfaceId: Long): SlaacConfiguration? {
        if (
            packet.size < 56 || packet.version() != 6 ||
            (packet[6].toInt() and 0xFF) != 58 ||
            (packet[40].toInt() and 0xFF) != 134
        ) return null
        var prefix: ByteArray? = null
        var prefixLength = 0
        val dns = mutableListOf<Inet6Address>()
        var offset = 56
        while (offset + 2 <= packet.size) {
            val type = packet[offset].toInt() and 0xFF
            val length = (packet[offset + 1].toInt() and 0xFF) * 8
            if (length == 0 || offset + length > packet.size) break
            if (type == 3 && length == 32) {
                val flags = packet[offset + 3].toInt() and 0xFF
                if (flags and 0x40 != 0) {
                    prefixLength = packet[offset + 2].toInt() and 0xFF
                    prefix = packet.copyOfRange(offset + 16, offset + 32)
                }
            } else if (type == 25 && length >= 24) {
                var addressOffset = offset + 8
                while (addressOffset + 16 <= offset + length) {
                    dns += inet6(packet.copyOfRange(addressOffset, addressOffset + 16))
                    addressOffset += 16
                }
            }
            offset += length
        }
        val prefixBytes = prefix ?: return null
        if (prefixLength != 64) return null
        val address = prefixBytes.copyOf()
        ByteBuffer.wrap(address).order(ByteOrder.BIG_ENDIAN).putLong(8, interfaceId)
        return SlaacConfiguration(inet6(address), inet6(prefixBytes), prefixLength, dns)
    }

    fun isRouterAdvertisement(packet: ByteArray): Boolean =
        packet.size >= 41 &&
            packet.version() == 6 &&
            (packet[6].toInt() and 0xFF) == 58 &&
            (packet[40].toInt() and 0xFF) == 134

    private fun option(buffer: ByteBuffer, code: Int, value: ByteArray) {
        buffer.put(code.toByte()).put(value.size.toByte()).put(value)
    }

    private fun parseOptions(packet: ByteArray, start: Int): Map<Int, ByteArray> {
        val result = mutableMapOf<Int, ByteArray>()
        var offset = start
        while (offset < packet.size) {
            val code = packet[offset++].toInt() and 0xFF
            if (code == 255) break
            if (code == 0) continue
            if (offset >= packet.size) break
            val length = packet[offset++].toInt() and 0xFF
            if (offset + length > packet.size) break
            result[code] = packet.copyOfRange(offset, offset + length)
            offset += length
        }
        return result
    }

    private fun syntheticHardwareAddress(peerId: String): ByteArray {
        var value = 0xcbf29ce484222325UL
        peerId.toByteArray(StandardCharsets.US_ASCII).forEach {
            value = (value xor it.toUByte().toULong()) * 0x100000001b3UL
        }
        return ByteArray(6) { index -> (value shr ((5 - index) * 8)).toByte() }.also {
            it[0] = ((it[0].toInt() or 0x02) and 0xFE).toByte()
        }
    }

    private fun ipv4UdpPacket(
        source: ByteArray,
        destination: ByteArray,
        sourcePort: Int,
        destinationPort: Int,
        payload: ByteArray,
    ): ByteArray {
        val udpLength = 8 + payload.size
        val totalLength = 20 + udpLength
        val packet = ByteBuffer.allocate(totalLength).order(ByteOrder.BIG_ENDIAN)
        packet.put(0x45).put(0).putShort(totalLength.toShort()).putShort(0).putShort(0)
        packet.put(64).put(17).putShort(0).put(source).put(destination)
        packet.putShort(sourcePort.toShort()).putShort(destinationPort.toShort())
            .putShort(udpLength.toShort()).putShort(0).put(payload)
        val bytes = packet.array()
        ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).putShort(10, internetChecksum(bytes.copyOfRange(0, 20)).toShort())
        return bytes
    }

    private fun internetChecksum(bytes: ByteArray): Int {
        var sum = 0L
        var offset = 0
        while (offset + 1 < bytes.size) {
            sum += (((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)).toLong()
            offset += 2
        }
        if (offset < bytes.size) sum += (bytes[offset].toInt() and 0xFF).toLong() shl 8
        while (sum ushr 16 != 0L) sum = (sum and 0xFFFF) + (sum ushr 16)
        return sum.inv().toInt() and 0xFFFF
    }

    private fun ByteArray.version(): Int = (this[0].toInt() ushr 4) and 0x0F
    private fun inet4(bytes: ByteArray): Inet4Address = InetAddress.getByAddress(bytes) as Inet4Address
    private fun inet6(bytes: ByteArray): Inet6Address = InetAddress.getByAddress(bytes) as Inet6Address
}
