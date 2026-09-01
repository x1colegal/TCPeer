"""DHCPv4 and SLAAC client messages carried as raw IP over TCPeer."""

from __future__ import annotations

from dataclasses import dataclass
import hashlib
import ipaddress
import secrets
import struct

from tcppeer.dhcp import DHCP_MAGIC, FIXED, OPTION_DNS, OPTION_END, OPTION_MESSAGE_TYPE, OPTION_REQUESTED_IP, OPTION_SERVER_ID, OPTION_SUBNET_MASK, parse_message
from tcppeer.ra import internet_checksum


@dataclass(frozen=True)
class AddressLease:
    address: ipaddress.IPv4Address
    prefix_length: int
    server: ipaddress.IPv4Address
    dns: tuple[str, ...]
    transaction_id: int


@dataclass(frozen=True)
class SlaacLease:
    address: ipaddress.IPv6Address
    prefix: ipaddress.IPv6Network
    dns: tuple[str, ...]


def transaction_id() -> int:
    return secrets.randbits(32)


def _option(code: int, value: bytes) -> bytes:
    return bytes((code, len(value))) + value


def _client_payload(peer_id: str, xid: int, kind: int, requested=None, server=None) -> bytes:
    client_id = peer_id.encode("ascii")
    hardware = bytearray(hashlib.sha256(client_id).digest()[:6])
    hardware[0] = (hardware[0] | 2) & 0xfe
    fixed = FIXED.pack(1, 1, 6, 0, xid, 0, 0x8000, *(b"\0" * 4 for _ in range(4)), bytes(hardware).ljust(16, b"\0"), b"\0" * 64, b"\0" * 128)
    options = _option(OPTION_MESSAGE_TYPE, bytes((kind,))) + _option(61, b"\0" + client_id)
    if requested is not None:
        options += _option(OPTION_REQUESTED_IP, requested.packed)
    if server is not None:
        options += _option(OPTION_SERVER_ID, server.packed)
    return (fixed + DHCP_MAGIC + options + bytes((OPTION_END,))).ljust(300, b"\0")


def _udp_packet(payload: bytes) -> bytes:
    udp_length = 8 + len(payload)
    udp = struct.pack("!HHHH", 68, 67, udp_length, 0) + payload
    header = struct.pack("!BBHHHBBH4s4s", 0x45, 0, 20 + udp_length, 0, 0, 64, 17, 0, b"\0" * 4, b"\xff" * 4)
    header = header[:10] + struct.pack("!H", internet_checksum(header)) + header[12:]
    return header + udp


def dhcp_discover(peer_id: str, xid: int) -> bytes:
    return _udp_packet(_client_payload(peer_id, xid, 1))


def dhcp_request(peer_id: str, lease: AddressLease) -> bytes:
    return _udp_packet(_client_payload(peer_id, lease.transaction_id, 3, lease.address, lease.server))


def parse_dhcp(packet: bytes, xid: int, kind: int) -> AddressLease | None:
    if len(packet) < 28 or packet[0] >> 4 != 4 or packet[9] != 17:
        return None
    offset = (packet[0] & 15) * 4
    if len(packet) < offset + 8 or struct.unpack_from("!HH", packet, offset) != (67, 68):
        return None
    message = parse_message(packet[offset + 8:])
    if message.xid != xid or message.message_type != kind:
        return None
    mask = message.options.get(OPTION_SUBNET_MASK, b"\xff\xff\xff\0")
    prefix = sum(byte.bit_count() for byte in mask)
    server_raw = message.options.get(OPTION_SERVER_ID)
    if not server_raw:
        return None
    dns_raw = message.options.get(OPTION_DNS, b"")
    dns = tuple(str(ipaddress.IPv4Address(dns_raw[pos:pos + 4])) for pos in range(0, len(dns_raw), 4) if len(dns_raw[pos:pos + 4]) == 4)
    return AddressLease(message.yiaddr, prefix, ipaddress.IPv4Address(server_raw), dns, xid)


def router_solicitation() -> bytes:
    source = ipaddress.IPv6Address("::").packed
    destination = ipaddress.IPv6Address("ff02::2").packed
    body = struct.pack("!BBHI", 133, 0, 0, 0)
    pseudo = source + destination + struct.pack("!I3xB", len(body), 58)
    body = body[:2] + struct.pack("!H", internet_checksum(pseudo + body)) + body[4:]
    return struct.pack("!IHBB16s16s", 6 << 28, len(body), 58, 255, source, destination) + body


def parse_ra(packet: bytes, interface_id: int) -> SlaacLease | None:
    if len(packet) < 56 or packet[0] >> 4 != 6 or packet[6] != 58 or packet[40] != 134:
        return None
    prefix = None
    dns: list[str] = []
    offset = 56
    while offset + 2 <= len(packet):
        kind, units = packet[offset], packet[offset + 1]
        length = units * 8
        if not length or offset + length > len(packet):
            break
        if kind == 3 and length == 32 and packet[offset + 2] == 64 and packet[offset + 3] & 0x40:
            prefix = ipaddress.IPv6Network((ipaddress.IPv6Address(packet[offset + 16:offset + 32]), 64), strict=False)
        elif kind == 25:
            dns.extend(str(ipaddress.IPv6Address(packet[pos:pos + 16])) for pos in range(offset + 8, offset + length, 16) if pos + 16 <= offset + length)
        offset += length
    if prefix is None:
        return None
    address = ipaddress.IPv6Address(int(prefix.network_address) | (interface_id & ((1 << 64) - 1)))
    return SlaacLease(address, prefix, tuple(dns))
