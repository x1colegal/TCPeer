"""Raw IPv4/UDP helpers used for DHCP packets traversing TUN."""

from __future__ import annotations

import ipaddress
import struct

from .ra import internet_checksum

IPPROTO_UDP = 17
DHCP_SERVER_PORT = 67
DHCP_CLIENT_PORT = 68


class PacketError(ValueError):
    """Raised for malformed inner IP packets."""


def extract_dhcp_payload(packet: bytes) -> bytes | None:
    if len(packet) < 28 or packet[0] >> 4 != 4:
        return None
    header_length = (packet[0] & 0x0F) * 4
    if header_length < 20 or len(packet) < header_length + 8 or packet[9] != IPPROTO_UDP:
        return None
    source_port, destination_port, udp_length = struct.unpack_from("!HHH", packet, header_length)
    if source_port != DHCP_CLIENT_PORT or destination_port != DHCP_SERVER_PORT:
        return None
    if udp_length < 8 or header_length + udp_length > len(packet):
        raise PacketError("invalid inner UDP length")
    return packet[header_length + 8:header_length + udp_length]


def build_dhcp_packet(payload: bytes, source: ipaddress.IPv4Address, destination: ipaddress.IPv4Address) -> bytes:
    udp_length = 8 + len(payload)
    udp = struct.pack("!HHHH", DHCP_SERVER_PORT, DHCP_CLIENT_PORT, udp_length, 0) + payload
    pseudo = source.packed + destination.packed + struct.pack("!BBH", 0, IPPROTO_UDP, udp_length)
    udp_checksum = internet_checksum(pseudo + udp)
    if udp_checksum == 0:
        udp_checksum = 0xFFFF
    udp = udp[:6] + struct.pack("!H", udp_checksum) + udp[8:]
    total_length = 20 + len(udp)
    header = struct.pack(
        "!BBHHHBBH4s4s", 0x45, 0, total_length, 0, 0, 64,
        IPPROTO_UDP, 0, source.packed, destination.packed,
    )
    checksum = internet_checksum(header)
    header = header[:10] + struct.pack("!H", checksum) + header[12:]
    return header + udp
