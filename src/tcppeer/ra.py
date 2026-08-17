"""IPv6 Router Advertisement construction for a layer-3 TUN device."""

from __future__ import annotations

import ipaddress
import struct
from typing import Iterable

ICMPV6_ROUTER_ADVERTISEMENT = 134
IPPROTO_ICMPV6 = 58
ALL_NODES = ipaddress.ip_address("ff02::1")


def internet_checksum(data: bytes) -> int:
    if len(data) % 2:
        data += b"\0"
    total = sum(struct.unpack(f"!{len(data) // 2}H", data))
    while total >> 16:
        total = (total & 0xFFFF) + (total >> 16)
    return (~total) & 0xFFFF


def _prefix_option(prefix: ipaddress.IPv6Network, preferred: int, valid: int) -> bytes:
    flags = 0xC0  # On-Link and Autonomous.
    return struct.pack("!BBBBIII16s", 3, 4, prefix.prefixlen, flags, valid, preferred, 0, prefix.network_address.packed)


def _rdnss_option(addresses: Iterable[ipaddress.IPv6Address], lifetime: int) -> bytes:
    packed = b"".join(address.packed for address in addresses)
    if not packed:
        return b""
    return struct.pack("!BBHI", 25, 1 + len(packed) // 8, 0, lifetime) + packed


def build_router_advertisement(
    source: ipaddress.IPv6Address,
    prefix: ipaddress.IPv6Network,
    router_lifetime: int,
    preferred_lifetime: int,
    valid_lifetime: int,
    dns: Iterable[str] = (),
    destination: ipaddress.IPv6Address = ALL_NODES,
) -> bytes:
    """Build a complete IPv6 packet containing one valid ICMPv6 RA."""
    if prefix.prefixlen != 64:
        raise ValueError("SLAAC prefix must be /64")
    if preferred_lifetime > valid_lifetime:
        raise ValueError("preferred lifetime cannot exceed valid lifetime")
    dns_addresses = tuple(
        address for address in (ipaddress.ip_address(item) for item in dns)
        if isinstance(address, ipaddress.IPv6Address)
    )
    body = struct.pack(
        "!BBHBBHII", ICMPV6_ROUTER_ADVERTISEMENT, 0, 0, 64, 0,
        router_lifetime, 0, 0,
    )
    body += _prefix_option(prefix, preferred_lifetime, valid_lifetime)
    body += _rdnss_option(dns_addresses, router_lifetime)
    pseudo = source.packed + destination.packed + struct.pack("!I3xB", len(body), IPPROTO_ICMPV6)
    checksum = internet_checksum(pseudo + body)
    body = body[:2] + struct.pack("!H", checksum) + body[4:]
    header = struct.pack(
        "!IHBB16s16s", 6 << 28, len(body), IPPROTO_ICMPV6, 255,
        source.packed, destination.packed,
    )
    return header + body


def is_router_solicitation(packet: bytes) -> bool:
    return (
        len(packet) >= 41
        and packet[0] >> 4 == 6
        and packet[6] == IPPROTO_ICMPV6
        and packet[40] == 133
    )
