package com.tcppeer.android.protocol

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TcpPeerProtocolTest {
    private class WriteCountingOutput : ByteArrayOutputStream() {
        var writes = 0

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            writes++
            super.write(bytes, offset, length)
        }
    }

    @Test
    fun controlIsAsciiCleartext() {
        val encoded = ControlMessage("AUTH", linkedMapOf(
            "Network" to "home",
            "Peer-ID" to "android",
        )).encode()
        val text = encoded.toString(Charsets.US_ASCII)
        assertFalse(text.contains("Secret:"))
        assertTrue(text.endsWith("\r\n\r\n"))
        assertEquals("android", TcpPeerProtocol.readControl(ByteArrayInputStream(encoded)).field("Peer-ID"))
    }

    @Test
    fun authProofDoesNotExposeSecret() {
        val proof = AuthProof.create("visible-secret", "home", "android", "abc123")
        assertEquals(64, proof.length)
        assertFalse(proof.contains("visible-secret"))
        assertEquals(proof, AuthProof.create("visible-secret", "home", "android", "abc123"))
    }

    @Test
    fun binaryIpv4AndIpv6RoundTrip() {
        listOf(4, 6).forEach { version ->
            val packet = ByteArray(64).also { it[0] = (version shl 4).toByte() }
            val output = ByteArrayOutputStream()
            TcpPeerProtocol.writeData(output, packet)
            assertArrayEquals(packet, TcpPeerProtocol.readData(ByteArrayInputStream(output.toByteArray())))
        }
    }

    @Test
    fun dataHeaderAndPayloadUseOneOutputWrite() {
        val packet = ByteArray(1_400).also { it[0] = 0x60 }
        val output = WriteCountingOutput()
        TcpPeerProtocol.writeData(output, packet)
        assertEquals(1, output.writes)
        assertArrayEquals(packet, TcpPeerProtocol.readData(ByteArrayInputStream(output.toByteArray())))
    }

    @Test
    fun linkLocalIpv6IsNotUsable() {
        assertFalse(TransportPolicy.isUsableIpv6(InetAddress.getByName("fe80::1")))
        assertTrue(TransportPolicy.isUsableIpv6(InetAddress.getByName("2001:db8::1")))
        assertTrue(TransportPolicy.isUsableIpv6(InetAddress.getByName("fd00::1")))
        assertFalse(TransportPolicy.isPublicIpv6(InetAddress.getByName("fd00::1")))
        assertTrue(TransportPolicy.isPublicIpv6(InetAddress.getByName("2001:db8::1")))
        assertEquals(DirectFamily.IPV4, TransportPolicy.choosePublic("fd00::1", "2001:db8::2"))
        assertEquals(DirectFamily.IPV6, TransportPolicy.choosePublic("2001:db8::1", "2001:db8::2"))
    }

    @Test
    fun routerSolicitationIsBinaryIpv6() {
        val packet = AddressNegotiation.routerSolicitation()
        assertEquals(6, (packet[0].toInt() ushr 4) and 0x0F)
        assertEquals(58, packet[6].toInt())
        assertEquals(133, packet[40].toInt() and 0xFF)
    }

    @Test
    fun coordinatorAddressAcceptsDnsName() {
        val addresses = TransportPolicy.resolveTcpAddresses("localhost")
        assertTrue(addresses.isNotEmpty())
    }

    @Test
    fun coordinatorAddressAcceptsBracketedIpv6() {
        val addresses = TransportPolicy.resolveTcpAddresses("[::1]")
        assertTrue(addresses.any { it.hostAddress?.contains(':') == true })
    }

    @Test
    fun activeNetworkAddressesExcludeStaleInterfaces() {
        val currentIpv4 = InetAddress.getByName("192.0.2.20")
        val currentIpv6 = InetAddress.getByName("2001:db8::20")
        val (ipv4, ipv6) = TransportPolicy.localAddresses(listOf(currentIpv4, currentIpv6))
        assertEquals(listOf(currentIpv4), ipv4)
        assertEquals(listOf(currentIpv6), ipv6)
    }
}
