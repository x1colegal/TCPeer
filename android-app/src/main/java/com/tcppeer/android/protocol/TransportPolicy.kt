package com.tcppeer.android.protocol

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface

enum class DirectFamily { IPV4, IPV6 }

object TransportPolicy {
    fun resolveTcpAddresses(host: String): List<InetAddress> {
        val normalized = host.trim().removeSurrounding("[", "]")
        require(normalized.isNotEmpty()) { "Coordinator DNS name or IP address is required" }
        return try {
            InetAddress.getAllByName(normalized).toList()
        } catch (error: java.net.UnknownHostException) {
            throw IllegalArgumentException("Cannot resolve coordinator DNS name: $host", error)
        }
    }

    fun isUsableIpv6(address: InetAddress): Boolean = address is Inet6Address &&
        !address.isAnyLocalAddress &&
        !address.isLoopbackAddress &&
        !address.isMulticastAddress &&
        !address.isLinkLocalAddress

    fun isPublicIpv6(address: InetAddress): Boolean = isUsableIpv6(address) &&
        (address.address[0].toInt() and 0xFE) != 0xFC

    fun isPublicIpv4(address: InetAddress): Boolean = address is Inet4Address &&
        !address.isAnyLocalAddress && !address.isLoopbackAddress &&
        !address.isLinkLocalAddress && !address.isSiteLocalAddress

    fun localAddresses(activeAddresses: Iterable<InetAddress>? = null): Pair<List<Inet4Address>, List<Inet6Address>> {
        val ipv4 = mutableListOf<Inet4Address>()
        val ipv6 = mutableListOf<Inet6Address>()
        val addresses = activeAddresses ?: NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
        addresses.forEach { address ->
                when (address) {
                    is Inet4Address -> if (!address.isLoopbackAddress) ipv4 += address
                    is Inet6Address -> if (isUsableIpv6(address)) ipv6 += address
                }
            }
        return ipv4 to ipv6
    }

    fun choose(localIpv6: List<Inet6Address>, remoteIpv6: String?): DirectFamily {
        val remote = runCatching { InetAddress.getByName(remoteIpv6) }.getOrNull()
        return if (localIpv6.any(::isUsableIpv6) && remote != null && isUsableIpv6(remote)) {
            DirectFamily.IPV6
        } else {
            DirectFamily.IPV4
        }
    }

    fun choosePublic(localIpv6: String?, remoteIpv6: String?): DirectFamily {
        val local = runCatching { InetAddress.getByName(localIpv6) }.getOrNull()
        val remote = runCatching { InetAddress.getByName(remoteIpv6) }.getOrNull()
        return if (local != null && remote != null && isPublicIpv6(local) && isPublicIpv6(remote)) {
            DirectFamily.IPV6
        } else DirectFamily.IPV4
    }
}
