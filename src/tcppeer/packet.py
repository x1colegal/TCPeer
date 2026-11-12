"""Raw IPv4/UDP helpers used for DHCP packets traversing TUN."""

from __future__ import annotations

import ipaddress
import struct

from .ra import internet_checksum

IPPROTO_UDP = 17
IPPROTO_TCP = 6
DHCP_SERVER_PORT = 67
DHCP_CLIENT_PORT = 68


class PacketError(ValueError):
    """Raised for malformed inner IP packets."""


def build_tcp_reset(packet: bytes) -> bytes | None:
    """Generate an RFC 793 TCP RST packet replying to *packet*, or None if not applicable."""
    if not packet:
        return None
    version = packet[0] >> 4
    if version == 4:
        if len(packet) < 40:
            return None
        ihl = (packet[0] & 0x0F) * 4
        if ihl < 20 or len(packet) < ihl + 20:
            return None
        if packet[9] != IPPROTO_TCP:
            return None
        total_len = struct.unpack_from("!H", packet, 2)[0]
        src_ip = packet[12:16]
        dst_ip = packet[16:20]
        src_port, dst_port, seq, ack = struct.unpack_from("!HHII", packet, ihl)
        data_offset = (packet[ihl + 12] >> 4) * 4
        if data_offset < 20 or len(packet) < ihl + data_offset:
            return None
        flags = packet[ihl + 13]
        if flags & 0x04:  # Do not respond to an existing RST
            return None
        payload_len = max(0, total_len - ihl - data_offset)
        if flags & 0x10:  # ACK bit set
            rst_seq = ack
            rst_ack = 0
            rst_flags = 0x04  # RST
        else:
            seg_len = payload_len + (1 if (flags & 0x03) else 0)
            rst_seq = 0
            rst_ack = (seq + seg_len) & 0xFFFFFFFF
            rst_flags = 0x14  # RST | ACK
        rst_tcp = bytearray(20)
        struct.pack_into(
            "!HHIIBBHHH",
            rst_tcp,
            0,
            dst_port,
            src_port,
            rst_seq,
            rst_ack,
            (5 << 4),
            rst_flags,
            0,
            0,
            0,
        )
        pseudo = dst_ip + src_ip + struct.pack("!BBH", 0, IPPROTO_TCP, 20)
        tcp_csum = internet_checksum(pseudo + bytes(rst_tcp))
        struct.pack_into("!H", rst_tcp, 16, tcp_csum)
        rst_ip = bytearray(20)
        struct.pack_into(
            "!BBHHHBBH4s4s",
            rst_ip,
            0,
            0x45,
            0,
            40,
            0,
            0x4000,
            64,
            IPPROTO_TCP,
            0,
            dst_ip,
            src_ip,
        )
        ip_csum = internet_checksum(bytes(rst_ip))
        struct.pack_into("!H", rst_ip, 10, ip_csum)
        return bytes(rst_ip) + bytes(rst_tcp)

    elif version == 6:
        if len(packet) < 60:
            return None
        if packet[6] != IPPROTO_TCP:
            return None
        payload_len = struct.unpack_from("!H", packet, 4)[0]
        src_ip = packet[8:24]
        dst_ip = packet[24:40]
        src_port, dst_port, seq, ack = struct.unpack_from("!HHII", packet, 40)
        data_offset = (packet[52] >> 4) * 4
        if data_offset < 20 or len(packet) < 40 + data_offset:
            return None
        flags = packet[53]
        if flags & 0x04:  # Do not respond to an existing RST
            return None
        tcp_payload_len = max(0, payload_len - data_offset)
        if flags & 0x10:  # ACK bit set
            rst_seq = ack
            rst_ack = 0
            rst_flags = 0x04  # RST
        else:
            seg_len = tcp_payload_len + (1 if (flags & 0x03) else 0)
            rst_seq = 0
            rst_ack = (seq + seg_len) & 0xFFFFFFFF
            rst_flags = 0x14  # RST | ACK
        rst_tcp = bytearray(20)
        struct.pack_into(
            "!HHIIBBHHH",
            rst_tcp,
            0,
            dst_port,
            src_port,
            rst_seq,
            rst_ack,
            (5 << 4),
            rst_flags,
            0,
            0,
            0,
        )
        pseudo = dst_ip + src_ip + struct.pack("!I3xB", 20, IPPROTO_TCP)
        tcp_csum = internet_checksum(pseudo + bytes(rst_tcp))
        struct.pack_into("!H", rst_tcp, 16, tcp_csum)
        rst_ip = struct.pack("!IHBB16s16s", 6 << 28, 20, IPPROTO_TCP, 64, dst_ip, src_ip)
        return rst_ip + bytes(rst_tcp)

    return None


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
