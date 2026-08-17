"""TCPeer cleartext control protocol and direct binary DATA framing."""

from __future__ import annotations

from dataclasses import dataclass
import struct
from typing import Mapping

PROTOCOL = "TCPeer/1.0"
BLOCK_END = b"\r\n\r\n"
MAX_CONTROL_SIZE = 16_384
DATA_MAGIC = b"TCPD"
DATA_VERSION = 1
DATA_HEADER = struct.Struct("!4sBBI")
MAX_PACKET_SIZE = 65_535

COMMANDS = frozenset(
    {
        "AUTH", "AUTH-CHALLENGE", "AUTH-PROOF", "AUTH-OK", "AUTH-ERROR", "REGISTER", "PEER-INFO",
        "ENDPOINT-INFO", "ENDPOINT-QUERY", "PUNCH-READY", "PUNCH-GO", "PING", "PONG",
        "KEEPALIVE", "ERROR", "DISCONNECT",
    }
)


class ProtocolError(ValueError):
    """Raised for malformed or forbidden TCPeer protocol input."""


@dataclass(frozen=True)
class ControlMessage:
    command: str
    fields: Mapping[str, str]

    def encode(self) -> bytes:
        command = self.command.upper()
        if command not in COMMANDS:
            raise ProtocolError(f"unknown command: {command}")
        lines = [f"{PROTOCOL} {command}"]
        for name, value in self.fields.items():
            if not name or ":" in name or "\r" in name or "\n" in name:
                raise ProtocolError("invalid field name")
            if "\r" in value or "\n" in value:
                raise ProtocolError("field values cannot contain line breaks")
            lines.append(f"{name}: {value}")
        try:
            encoded = ("\r\n".join(lines) + "\r\n\r\n").encode("ascii")
        except UnicodeEncodeError as exc:
            raise ProtocolError("control messages must contain ASCII only") from exc
        if len(encoded) > MAX_CONTROL_SIZE:
            raise ProtocolError("control message exceeds maximum size")
        return encoded

    def get(self, name: str, default: str | None = None) -> str | None:
        wanted = name.casefold()
        for key, value in self.fields.items():
            if key.casefold() == wanted:
                return value
        return default


def parse_control(block: bytes) -> ControlMessage:
    if block.startswith(DATA_MAGIC):
        raise ProtocolError("DATA is forbidden on control connections")
    if len(block) > MAX_CONTROL_SIZE:
        raise ProtocolError("control message exceeds maximum size")
    try:
        text = block.decode("ascii")
    except UnicodeDecodeError as exc:
        raise ProtocolError("control messages must contain ASCII only") from exc
    if not text.endswith("\r\n\r\n"):
        raise ProtocolError("control message is not terminated by an empty CRLF line")
    lines = text[:-4].split("\r\n")
    parts = lines[0].split(" ", 1)
    if len(parts) != 2 or parts[0] != PROTOCOL:
        raise ProtocolError("invalid protocol header")
    command = parts[1].upper()
    if command not in COMMANDS:
        raise ProtocolError(f"unknown command: {command}")
    fields: dict[str, str] = {}
    for line in lines[1:]:
        if ": " not in line:
            raise ProtocolError("invalid control field")
        name, value = line.split(": ", 1)
        if not name or name.casefold() in (key.casefold() for key in fields):
            raise ProtocolError("invalid or duplicate control field")
        fields[name] = value
    return ControlMessage(command, fields)


async def read_control(reader, max_size: int = MAX_CONTROL_SIZE) -> ControlMessage:
    try:
        block = await reader.readuntil(BLOCK_END)
    except Exception as exc:
        raise ProtocolError("incomplete control message") from exc
    if len(block) > max_size:
        raise ProtocolError("control message exceeds maximum size")
    return parse_control(block)


def encode_data(packet: bytes) -> bytes:
    if not packet or len(packet) > MAX_PACKET_SIZE:
        raise ProtocolError("invalid IP packet length")
    family = packet[0] >> 4
    if family not in (4, 6):
        raise ProtocolError("DATA payload is not an IPv4 or IPv6 packet")
    return DATA_HEADER.pack(DATA_MAGIC, DATA_VERSION, family, len(packet)) + packet


def decode_data(frame: bytes) -> bytes:
    if len(frame) < DATA_HEADER.size:
        raise ProtocolError("truncated DATA frame")
    magic, version, family, length = DATA_HEADER.unpack_from(frame)
    packet = frame[DATA_HEADER.size:]
    if magic != DATA_MAGIC or version != DATA_VERSION:
        raise ProtocolError("invalid DATA frame header")
    if length != len(packet) or not packet or length > MAX_PACKET_SIZE:
        raise ProtocolError("invalid DATA frame length")
    if family not in (4, 6) or packet[0] >> 4 != family:
        raise ProtocolError("DATA family does not match the IP packet")
    return packet


async def read_data(reader) -> bytes:
    header = await reader.readexactly(DATA_HEADER.size)
    magic, version, family, length = DATA_HEADER.unpack(header)
    if magic != DATA_MAGIC or version != DATA_VERSION or family not in (4, 6):
        raise ProtocolError("invalid DATA frame header")
    if not 1 <= length <= MAX_PACKET_SIZE:
        raise ProtocolError("invalid DATA frame length")
    return decode_data(header + await reader.readexactly(length))
