"""TCPPeerPing carried directly in IPv6 Next Header 99 packets."""

from __future__ import annotations

from dataclasses import dataclass
import ipaddress
import struct

NEXT_HEADER = 99
MAGIC = b"TPP1"
VERSION = 1
ECHO_REQUEST = 1
ECHO_REPLY = 2
_IPV6 = struct.Struct("!IHBB16s16s")
_TPP = struct.Struct("!4sBBHQQ")


@dataclass(frozen=True)
class TppMessage:
    kind: int
    identifier: int
    timestamp_ns: int
    source: ipaddress.IPv6Address
    destination: ipaddress.IPv6Address


def parse_tpp(packet: bytes) -> TppMessage | None:
    if len(packet) < _IPV6.size + _TPP.size or packet[0] >> 4 != 6 or packet[6] != NEXT_HEADER:
        return None
    _flow, payload_length, _next, _hop, source, destination = _IPV6.unpack_from(packet)
    if payload_length != _TPP.size or len(packet) < _IPV6.size + payload_length:
        return None
    magic, version, kind, _reserved, identifier, timestamp_ns = _TPP.unpack_from(packet, _IPV6.size)
    if magic != MAGIC or version != VERSION or kind not in {ECHO_REQUEST, ECHO_REPLY}:
        return None
    return TppMessage(
        kind, identifier, timestamp_ns,
        ipaddress.IPv6Address(source), ipaddress.IPv6Address(destination),
    )


def build_tpp(
    source: ipaddress.IPv6Address,
    destination: ipaddress.IPv6Address,
    kind: int,
    identifier: int,
    timestamp_ns: int,
) -> bytes:
    payload = _TPP.pack(MAGIC, VERSION, kind, 0, identifier, timestamp_ns)
    header = _IPV6.pack(
        6 << 28, len(payload), NEXT_HEADER, 64,
        source.packed, destination.packed,
    )
    return header + payload


def build_reply(packet: bytes) -> bytes | None:
    message = parse_tpp(packet)
    if message is None or message.kind != ECHO_REQUEST:
        return None
    return build_tpp(
        message.destination, message.source, ECHO_REPLY,
        message.identifier, message.timestamp_ns,
    )
