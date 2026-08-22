"""TCPeer cleartext control protocol and TCPD ASCII DATA framing."""

from __future__ import annotations

from dataclasses import dataclass
import struct
from typing import Mapping

PROTOCOL = "TCPeer/1.0"
BLOCK_END = b"\r\n\r\n"
MAX_CONTROL_SIZE = 16_384
DATA_MAGIC = b"TCPD"
DATA_VERSION = 1
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


def _checksum(data: bytes) -> int:
    if len(data) & 1:
        data += b"\x00"
    total = 0
    for i in range(0, len(data), 2):
        total += (data[i] << 8) | data[i + 1]
        total = (total & 0xffff) + (total >> 16)
    return (~total) & 0xffff


def _tcp_flags(value: int) -> str:
    names = (
        (0x100, "NS"),
        (0x080, "CWR"),
        (0x040, "ECE"),
        (0x020, "URG"),
        (0x010, "ACK"),
        (0x008, "PSH"),
        (0x004, "RST"),
        (0x002, "SYN"),
        (0x001, "FIN"),
    )
    result = [name for bit, name in names if value & bit]
    return ",".join(result) if result else "NONE"


def _tcp_flags_value(text: str) -> int:
    values = {
        "NS": 0x100, "CWR": 0x080, "ECE": 0x040,
        "URG": 0x020, "ACK": 0x010, "PSH": 0x008,
        "RST": 0x004, "SYN": 0x002, "FIN": 0x001,
    }
    result = 0
    for name in text.split(","):
        name = name.strip().upper()
        if name and name != "NONE":
            if name not in values:
                raise ProtocolError("invalid TCP flag")
            result |= values[name]
    return result


def _parse_packet(packet: bytes):
    import ipaddress

    if not packet:
        raise ProtocolError("empty IP packet")

    family = packet[0] >> 4

    if family == 6:
        if len(packet) < 40:
            raise ProtocolError("truncated IPv6 packet")

        first, payload_len, protocol, hop = struct.unpack_from("!IHBB", packet, 0)

        if len(packet) < 40 + payload_len:
            raise ProtocolError("truncated IPv6 payload")

        source = str(ipaddress.IPv6Address(packet[8:24]))
        destination = str(ipaddress.IPv6Address(packet[24:40]))

        traffic_class = (first >> 20) & 0xff
        flow_label = first & 0xfffff
        transport = packet[40:40 + payload_len]

        ip_fields = [
            ("Traffic Class", str(traffic_class)),
            ("Flow Label", str(flow_label)),
            ("Hop Limit", str(hop)),
        ]

    elif family == 4:
        if len(packet) < 20:
            raise ProtocolError("truncated IPv4 packet")

        ihl = (packet[0] & 0x0f) * 4
        if ihl < 20 or len(packet) < ihl:
            raise ProtocolError("invalid IPv4 header")

        total_len = struct.unpack_from("!H", packet, 2)[0]
        if total_len < ihl or len(packet) < total_len:
            raise ProtocolError("invalid IPv4 packet length")

        tos = packet[1]
        ident = struct.unpack_from("!H", packet, 4)[0]
        frag = struct.unpack_from("!H", packet, 6)[0]
        ttl = packet[8]
        protocol = packet[9]

        source = str(ipaddress.IPv4Address(packet[12:16]))
        destination = str(ipaddress.IPv4Address(packet[16:20]))

        options = packet[20:ihl]
        transport = packet[ihl:total_len]

        ip_fields = [
            ("Type Of Service", str(tos)),
            ("Identification", str(ident)),
            ("Fragment", str(frag)),
            ("TTL", str(ttl)),
            ("IP Options", options.hex()),
        ]

    else:
        raise ProtocolError("DATA is neither IPv4 nor IPv6")

    protocol_names = {
        1: "ICMP",
        6: "TCP",
        17: "UDP",
        33: "DCCP",
        58: "ICMPv6",
        132: "SCTP",
    }

    # Protocolos conhecidos recebem nome.
    # Qualquer outro Next Header / IPv4 Protocol continua funcionando
    # usando o próprio número no campo Protocol.
    name = protocol_names.get(protocol, str(protocol))

    fields = [
        ("Version", str(DATA_VERSION)),
        ("Family", f"IPv{family}"),
        ("Protocol", name),
        ("Source", source),
        ("Destination", destination),
        *ip_fields,
    ]

    # --------------------------------------------------------
    # TCP
    # --------------------------------------------------------
    if protocol == 6:
        if len(transport) < 20:
            raise ProtocolError("truncated TCP segment")

        sport, dport, seq, ack = struct.unpack_from("!HHII", transport, 0)

        offset = (transport[12] >> 4) * 4
        if offset < 20 or offset > len(transport):
            raise ProtocolError("invalid TCP data offset")

        flags = ((transport[12] & 1) << 8) | transport[13]
        window, checksum, urgent = struct.unpack_from("!HHH", transport, 14)

        options = transport[20:offset]
        payload = transport[offset:]

        fields += [
            ("Source Port", str(sport)),
            ("Destination Port", str(dport)),
            ("Sequence", str(seq)),
            ("Acknowledgment", str(ack)),
            ("Flags", _tcp_flags(flags)),
            ("Window", str(window)),
            ("Urgent Pointer", str(urgent)),
            ("TCP Options", options.hex()),
            ("Checksum", str(checksum)),
        ]

    # --------------------------------------------------------
    # UDP
    # --------------------------------------------------------
    elif protocol == 17:
        if len(transport) < 8:
            raise ProtocolError("truncated UDP datagram")

        sport, dport, length, checksum = struct.unpack_from("!HHHH", transport, 0)

        if length < 8 or length > len(transport):
            raise ProtocolError("invalid UDP length")

        payload = transport[8:length]

        fields += [
            ("Source Port", str(sport)),
            ("Destination Port", str(dport)),
            ("Checksum", str(checksum)),
        ]

    # --------------------------------------------------------
    # ICMP / ICMPv6
    #
    # Os 4 bytes fixos Type/Code/Checksum viram ASCII TCPD.
    # Tudo depois deles é payload cru.
    # --------------------------------------------------------
    elif protocol in (1, 58):
        if len(transport) < 4:
            raise ProtocolError("truncated ICMP packet")

        icmp_type, code, checksum = struct.unpack_from("!BBH", transport, 0)
        payload = transport[4:]

        fields += [
            ("Type", str(icmp_type)),
            ("Code", str(code)),
            ("Checksum", str(checksum)),
        ]

    # --------------------------------------------------------
    # SCTP
    # SCTP common header is represented as TCPD fields.
    # Chunks become the raw TCPD payload.
    # --------------------------------------------------------
    elif protocol == 132:
        if len(transport) < 12:
            raise ProtocolError("truncated SCTP packet")

        sport, dport, verification, checksum = struct.unpack_from("!HHII", transport, 0)
        payload = transport[12:]

        fields += [
            ("Source Port", str(sport)),
            ("Destination Port", str(dport)),
            ("Verification Tag", str(verification)),
            ("Checksum", str(checksum)),
        ]

    # --------------------------------------------------------
    # DCCP
    # DCCP has a variable transport header.  Store every
    # non-payload transport byte as ASCII hex metadata, never
    # as binary encapsulated header bytes.
    # --------------------------------------------------------
    elif protocol == 33:
        if len(transport) < 12:
            raise ProtocolError("truncated DCCP packet")

        sport, dport = struct.unpack_from("!HH", transport, 0)
        data_offset = transport[4] * 4

        if data_offset < 12 or data_offset > len(transport):
            raise ProtocolError("invalid DCCP data offset")

        dccp_metadata = transport[4:data_offset]
        payload = transport[data_offset:]

        fields += [
            ("Source Port", str(sport)),
            ("Destination Port", str(dport)),
            ("DCCP Fields", dccp_metadata.hex()),
        ]

    # --------------------------------------------------------
    # Qualquer outro IP Protocol / IPv6 Next Header.
    #
    # Não conhecemos a estrutura dele, portanto não inventamos header.
    # Todos os bytes depois do header IP são transportados crus.
    # --------------------------------------------------------
    else:
        payload = transport

    fields.append(("Payload Length", str(len(payload))))

    return fields, payload



def encode_data(packet: bytes) -> bytes:
    """Return the original IP packet without any TCPeer framing."""
    if not packet or len(packet) > MAX_PACKET_SIZE:
        raise ProtocolError("invalid IP packet length")

    version = packet[0] >> 4
    if version not in (4, 6):
        raise ProtocolError("DATA is neither IPv4 nor IPv6")

    return packet


def _parse_tcpd_header(header: bytes) -> dict[str, str]:
    try:
        text = header.decode("ascii")
    except UnicodeDecodeError as exc:
        raise ProtocolError("TCPD metadata is not ASCII") from exc

    lines = text.splitlines()

    if not lines or lines[0] != "TCPD":
        raise ProtocolError("invalid TCPD magic")

    result = {}

    for line in lines[1:]:
        if "=" not in line:
            raise ProtocolError("invalid TCPD field")
        name, value = line.split("=", 1)
        if not name or name in result:
            raise ProtocolError("invalid or duplicate TCPD field")
        result[name] = value

    if result.get("Version") != str(DATA_VERSION):
        raise ProtocolError("unsupported TCPD version")

    return result


def _rebuild_packet(fields: dict[str, str], payload: bytes) -> bytes:
    import ipaddress

    family_text = fields["Family"]
    protocol_name = fields["Protocol"].upper()

    protocols = {
        "ICMP": 1,
        "TCP": 6,
        "UDP": 17,
        "DCCP": 33,
        "ICMPV6": 58,
        "SCTP": 132,
    }

    if protocol_name in protocols:
        protocol = protocols[protocol_name]
    else:
        try:
            protocol = int(protocol_name)
        except ValueError as exc:
            raise ProtocolError("unsupported TCPD protocol") from exc

        if not 0 <= protocol <= 255:
            raise ProtocolError("invalid TCPD protocol number")

    source = ipaddress.ip_address(fields["Source"])
    destination = ipaddress.ip_address(fields["Destination"])

    # Portas só existem nos transportes que realmente as possuem.
    if protocol in (6, 17, 33, 132):
        sport = int(fields["Source Port"])
        dport = int(fields["Destination Port"])
    else:
        sport = dport = 0

    # --------------------------------------------------------
    # Rebuild transport header from TCPD ASCII metadata.
    # Payload itself stays completely untouched.
    # --------------------------------------------------------
    if protocol == 6:
        seq = int(fields["Sequence"])
        ack = int(fields["Acknowledgment"])
        flags = _tcp_flags_value(fields["Flags"])
        window = int(fields["Window"])
        urgent = int(fields["Urgent Pointer"])
        options = bytes.fromhex(fields.get("TCP Options", ""))

        if len(options) % 4:
            raise ProtocolError("invalid TCP options length")

        offset = 20 + len(options)
        if offset > 60:
            raise ProtocolError("TCP header too large")

        tcp = bytearray(offset)
        struct.pack_into("!HHII", tcp, 0, sport, dport, seq, ack)
        tcp[12] = ((offset // 4) << 4) | ((flags >> 8) & 1)
        tcp[13] = flags & 0xff
        struct.pack_into("!HHH", tcp, 14, window, 0, urgent)
        tcp[20:] = options

        transport = bytes(tcp) + payload

    elif protocol == 17:
        transport = struct.pack(
            "!HHHH",
            sport,
            dport,
            8 + len(payload),
            0,
        ) + payload

    elif protocol in (1, 58):
        icmp_type = int(fields["Type"])
        code = int(fields["Code"])

        # Checksum será recalculado abaixo.
        transport = struct.pack(
            "!BBH",
            icmp_type,
            code,
            0,
        ) + payload

    elif protocol == 132:
        verification = int(fields["Verification Tag"])
        checksum = int(fields["Checksum"])
        transport = struct.pack(
            "!HHII",
            sport,
            dport,
            verification,
            checksum,
        ) + payload

    elif protocol == 33:
        rest = bytes.fromhex(fields["DCCP Fields"])
        transport = struct.pack("!HH", sport, dport) + rest + payload

    else:
        # Protocolo desconhecido: os bytes transportados já são
        # exatamente o conteúdo posterior ao header IP.
        transport = payload

    # --------------------------------------------------------
    # Build IP header
    # --------------------------------------------------------
    if family_text == "IPv6":
        if source.version != 6 or destination.version != 6:
            raise ProtocolError("TCPD IPv6 address mismatch")

        tc = int(fields.get("Traffic Class", "0"))
        flow = int(fields.get("Flow Label", "0"))
        hop = int(fields.get("Hop Limit", "64"))

        first = (6 << 28) | ((tc & 0xff) << 20) | (flow & 0xfffff)

        # Recalculate TCP/UDP checksum because the original binary
        # transport header is deliberately not transported.
        if protocol in (6, 17, 58):
            pseudo = (
                source.packed +
                destination.packed +
                struct.pack("!I3xB", len(transport), protocol)
            )

            checksum = _checksum(pseudo + transport)
            t = bytearray(transport)

            if protocol == 6:
                struct.pack_into("!H", t, 16, checksum)
            elif protocol == 17:
                if checksum == 0:
                    checksum = 0xffff
                struct.pack_into("!H", t, 6, checksum)
            else:
                # ICMPv6 checksum
                struct.pack_into("!H", t, 2, checksum)

            transport = bytes(t)

        header = struct.pack(
            "!IHBB16s16s",
            first,
            len(transport),
            protocol,
            hop,
            source.packed,
            destination.packed,
        )

        return header + transport

    if family_text == "IPv4":
        if source.version != 4 or destination.version != 4:
            raise ProtocolError("TCPD IPv4 address mismatch")

        tos = int(fields.get("Type Of Service", "0"))
        ident = int(fields.get("Identification", "0"))
        frag = int(fields.get("Fragment", "0"))
        ttl = int(fields.get("TTL", "64"))
        options = bytes.fromhex(fields.get("IP Options", ""))

        if len(options) % 4:
            raise ProtocolError("invalid IPv4 options length")

        ihl = 20 + len(options)

        if protocol in (6, 17):
            pseudo = (
                source.packed +
                destination.packed +
                struct.pack("!BBH", 0, protocol, len(transport))
            )

            checksum = _checksum(pseudo + transport)

            t = bytearray(transport)
            if protocol == 6:
                struct.pack_into("!H", t, 16, checksum)
            else:
                struct.pack_into("!H", t, 6, checksum)
            transport = bytes(t)

        elif protocol == 1:
            # ICMPv4 não usa pseudo-header.
            t = bytearray(transport)
            struct.pack_into("!H", t, 2, 0)
            struct.pack_into("!H", t, 2, _checksum(bytes(t)))
            transport = bytes(t)

        total = ihl + len(transport)

        header = bytearray(ihl)
        struct.pack_into(
            "!BBHHHBBH4s4s",
            header,
            0,
            (4 << 4) | (ihl // 4),
            tos,
            total,
            ident,
            frag,
            ttl,
            protocol,
            0,
            source.packed,
            destination.packed,
        )

        header[20:] = options

        checksum = _checksum(bytes(header))
        struct.pack_into("!H", header, 10, checksum)

        return bytes(header) + transport

    raise ProtocolError("invalid TCPD Family")



def decode_data(frame: bytes) -> bytes:
    """RAW IP DATA has no TCPeer framing."""
    if not frame or len(frame) > MAX_PACKET_SIZE:
        raise ProtocolError("invalid IP packet length")

    version = frame[0] >> 4
    if version not in (4, 6):
        raise ProtocolError("DATA is neither IPv4 nor IPv6")

    return frame


async def read_data(reader) -> bytes:
    """
    Read exactly one raw IPv4/IPv6 packet.

    TCPeer framing overhead: ZERO bytes.

    Packet boundaries are obtained exclusively from the IP header
    already present in the stream.
    """

    try:
        first = await reader.readexactly(1)
    except Exception as exc:
        raise ProtocolError(
            "connection closed while reading IP version"
        ) from exc

    version = first[0] >> 4

    # --------------------------------------------------------
    # IPv4
    #
    # Need first 20 bytes to obtain:
    #   IHL
    #   Total Length
    # --------------------------------------------------------
    if version == 4:
        try:
            base = first + await reader.readexactly(19)
        except Exception as exc:
            raise ProtocolError(
                "connection closed inside IPv4 header"
            ) from exc

        ihl = (base[0] & 0x0f) * 4

        if ihl < 20 or ihl > 60:
            raise ProtocolError("invalid IPv4 IHL")

        total_length = int.from_bytes(base[2:4], "big")

        if total_length < ihl:
            raise ProtocolError("invalid IPv4 total length")

        if total_length > MAX_PACKET_SIZE:
            raise ProtocolError("IPv4 packet exceeds maximum size")

        remaining = total_length - 20

        try:
            tail = await reader.readexactly(remaining)
        except Exception as exc:
            raise ProtocolError(
                "connection closed inside IPv4 packet"
            ) from exc

        return base + tail

    # --------------------------------------------------------
    # IPv6
    #
    # Fixed header = 40 bytes.
    # Payload Length is bytes 4..5.
    #
    # Total packet length:
    #     40 + Payload Length
    # --------------------------------------------------------
    if version == 6:
        try:
            header = first + await reader.readexactly(39)
        except Exception as exc:
            raise ProtocolError(
                "connection closed inside IPv6 header"
            ) from exc

        payload_length = int.from_bytes(header[4:6], "big")
        total_length = 40 + payload_length

        if total_length > MAX_PACKET_SIZE:
            raise ProtocolError("IPv6 packet exceeds maximum size")

        try:
            payload = await reader.readexactly(payload_length)
        except Exception as exc:
            raise ProtocolError(
                "connection closed inside IPv6 packet"
            ) from exc

        return header + payload

    raise ProtocolError(
        f"invalid raw IP version in DATA stream: {version}"
    )

