package com.tcppeer.android.protocol

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object AuthProof {
    fun create(secret: String, network: String, peerId: String, nonce: String): String {
        val message = "TCPeer/1.0\n$network\n$peerId\n$nonce".toByteArray(Charsets.US_ASCII)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.US_ASCII), "HmacSHA256"))
        return mac.doFinal(message).joinToString("") { "%02x".format(it) }
    }
}
