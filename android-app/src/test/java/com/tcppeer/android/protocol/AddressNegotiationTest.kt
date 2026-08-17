package com.tcppeer.android.protocol

import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class AddressNegotiationTest {
    @Test
    fun parsesUnsignedIcmpv6RouterAdvertisementType() {
        val prefix = InetAddress.getByName("fdfe:cafe:cafe::").address
        val packet = ByteBuffer.allocate(88).order(ByteOrder.BIG_ENDIAN).apply {
            putInt(6 shl 28)
            putShort(48)
            put(58)
            put(255.toByte())
            put(InetAddress.getByName("fdfe:cafe:cafe::1").address)
            put(InetAddress.getByName("ff02::1").address)
            put(134.toByte())
            put(0)
            putShort(0)
            put(64)
            put(0)
            putShort(1800)
            putInt(0)
            putInt(0)
            put(3)
            put(4)
            put(64)
            put(0xC0.toByte())
            putInt(86400)
            putInt(3600)
            putInt(0)
            put(prefix)
        }.array()

        val configuration = AddressNegotiation.parseRouterAdvertisement(packet, 0x0102030405060708L)
        assertNotNull(configuration)
        configuration!!
        assertEquals("fdfe:cafe:cafe:0:102:304:506:708", configuration.address.hostAddress)
        assertEquals(64, configuration.prefixLength)
    }
}
