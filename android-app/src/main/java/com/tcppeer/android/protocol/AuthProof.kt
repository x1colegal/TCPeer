package com.tcppeer.android.protocol

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object AuthProof {
    private val secureRandom = SecureRandom()

    fun generateNonce(): String {
        val bytes = ByteArray(32)
        secureRandom.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun create(secret: String, network: String, peerId: String, nonce: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.US_ASCII), "HmacSHA256"))
        val material = "$CONTROL_PROTOCOL\n$network\n$peerId\n$nonce".toByteArray(Charsets.US_ASCII)
        return mac.doFinal(material).joinToString("") { "%02x".format(it) }
    }

    fun verify(secret: String, network: String, peerId: String, nonce: String, proof: String): Boolean {
        val expected = create(secret, network, peerId, nonce).toByteArray(Charsets.US_ASCII)
        val candidate = proof.lowercase().toByteArray(Charsets.US_ASCII)
        return MessageDigest.isEqual(expected, candidate)
    }
}
