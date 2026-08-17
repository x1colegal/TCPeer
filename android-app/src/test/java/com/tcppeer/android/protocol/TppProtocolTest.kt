package com.tcppeer.android.protocol

import java.net.Inet6Address
import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class TppProtocolTest {
    @Test
    fun requestUsesIpv6NextHeader99() {
        val source = InetAddress.getByName("fdfe:cafe:cafe::10") as Inet6Address
        val destination = InetAddress.getByName("fdfe:cafe:cafe::1") as Inet6Address
        val packet = TppProtocol.request(source, destination, 42, 123456789)
        assertEquals(6, packet[0].toInt() ushr 4)
        assertEquals(99, packet[6].toInt() and 0xFF)
        val message = TppProtocol.parse(packet)
        assertNotNull(message)
        assertEquals(TppProtocol.ECHO_REQUEST, message!!.type)
        assertEquals(42, message.identifier)
        assertEquals(123456789, message.timestampNanos)
        assertEquals(source, message.source)
        assertEquals(destination, message.destination)
    }
}
