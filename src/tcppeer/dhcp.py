"""Stateful DHCPv4 engine operating on raw payloads from a TUN device."""

from __future__ import annotations

from dataclasses import dataclass
import ipaddress
import struct
from typing import Iterable

from .state import Lease, StateStore

BOOTREQUEST = 1
BOOTREPLY = 2
DHCP_MAGIC = b"\x63\x82\x53\x63"
DHCPDISCOVER = 1
DHCPOFFER = 2
DHCPREQUEST = 3
DHCPACK = 5
DHCPNAK = 6
DHCPRELEASE = 7

OPTION_SUBNET_MASK = 1
OPTION_ROUTER = 3
OPTION_DNS = 6
OPTION_REQUESTED_IP = 50
OPTION_LEASE_TIME = 51
OPTION_MESSAGE_TYPE = 53
OPTION_SERVER_ID = 54
OPTION_CLIENT_ID = 61
OPTION_END = 255

FIXED = struct.Struct("!BBBBIHH4s4s4s4s16s64s128s")


class DhcpError(ValueError):
    """Raised for malformed DHCP input."""


@dataclass(frozen=True)
class DhcpMessage:
    op: int
    htype: int
    hlen: int
    xid: int
    flags: int
    ciaddr: ipaddress.IPv4Address
    yiaddr: ipaddress.IPv4Address
    siaddr: ipaddress.IPv4Address
    giaddr: ipaddress.IPv4Address
    chaddr: bytes
    options: dict[int, bytes]

    @property
    def message_type(self) -> int | None:
        value = self.options.get(OPTION_MESSAGE_TYPE)
        return value[0] if value and len(value) == 1 else None

    @property
    def client_id(self) -> str:
        value = self.options.get(OPTION_CLIENT_ID)
        identity = value if value else self.chaddr[: self.hlen]
        return identity.hex()


def parse_message(payload: bytes) -> DhcpMessage:
    if len(payload) < FIXED.size + 4:
        raise DhcpError("DHCP message is truncated")
    values = FIXED.unpack_from(payload)
    if payload[FIXED.size:FIXED.size + 4] != DHCP_MAGIC:
        raise DhcpError("invalid DHCP magic cookie")
    options: dict[int, bytes] = {}
    offset = FIXED.size + 4
    while offset < len(payload):
        code = payload[offset]
        offset += 1
        if code == 0:
            continue
        if code == OPTION_END:
            break
        if offset >= len(payload):
            raise DhcpError("truncated DHCP option")
        length = payload[offset]
        offset += 1
        if offset + length > len(payload):
            raise DhcpError("truncated DHCP option value")
        options[code] = payload[offset:offset + length]
        offset += length
    return DhcpMessage(
        op=values[0], htype=values[1], hlen=values[2], xid=values[4], flags=values[6],
        ciaddr=ipaddress.ip_address(values[7]), yiaddr=ipaddress.ip_address(values[8]),
        siaddr=ipaddress.ip_address(values[9]), giaddr=ipaddress.ip_address(values[10]),
        chaddr=values[11], options=options,
    )


def _option(code: int, value: bytes) -> bytes:
    return bytes((code, len(value))) + value


def build_reply(
    request: DhcpMessage,
    message_type: int,
    server: ipaddress.IPv4Address,
    lease: Lease | None,
    subnet: ipaddress.IPv4Network,
    lease_seconds: int,
    dns: Iterable[ipaddress.IPv4Address] = (),
) -> bytes:
    yiaddr = lease.address.packed if lease is not None and message_type != DHCPNAK else b"\0" * 4
    fixed = FIXED.pack(
        BOOTREPLY, request.htype, request.hlen, 0, request.xid, 0, request.flags,
        b"\0" * 4, yiaddr, server.packed, request.giaddr.packed,
        request.chaddr.ljust(16, b"\0")[:16], b"\0" * 64, b"\0" * 128,
    )
    options = [
        _option(OPTION_MESSAGE_TYPE, bytes((message_type,))),
        _option(OPTION_SERVER_ID, server.packed),
    ]
    if message_type in (DHCPOFFER, DHCPACK):
        options.extend(
            [
                _option(OPTION_SUBNET_MASK, subnet.netmask.packed),
                _option(OPTION_ROUTER, server.packed),
                _option(OPTION_LEASE_TIME, struct.pack("!I", lease_seconds)),
            ]
        )
        dns_bytes = b"".join(item.packed for item in dns)
        if dns_bytes:
            options.append(_option(OPTION_DNS, dns_bytes))
    return fixed + DHCP_MAGIC + b"".join(options) + bytes((OPTION_END,))


class DhcpServer:
    def __init__(self, store: StateStore, subnet, server, pool_start, pool_end, lease_seconds, dns=()):
        self.store = store
        self.subnet = subnet
        self.server = server
        self.pool_start = pool_start
        self.pool_end = pool_end
        self.lease_seconds = lease_seconds
        self.dns = tuple(ipaddress.ip_address(item) for item in dns if ipaddress.ip_address(item).version == 4)

    def handle(self, payload: bytes, now: int | None = None) -> bytes | None:
        request = parse_message(payload)
        if request.op != BOOTREQUEST:
            raise DhcpError("expected a DHCP client request")
        kind = request.message_type
        if kind == DHCPRELEASE:
            self.store.release_lease(request.client_id)
            return None
        if kind == DHCPDISCOVER:
            lease = self.store.allocate_lease(request.client_id, self.pool_start, self.pool_end, self.lease_seconds, offered=True, now=now)
            return build_reply(request, DHCPOFFER, self.server, lease, self.subnet, self.lease_seconds, self.dns)
        if kind == DHCPREQUEST:
            requested_raw = request.options.get(OPTION_REQUESTED_IP)
            requested = ipaddress.ip_address(requested_raw) if requested_raw and len(requested_raw) == 4 else None
            lease = self.store.get_lease(request.client_id)
            if lease is None:
                try:
                    lease = self.store.allocate_lease(request.client_id, self.pool_start, self.pool_end, self.lease_seconds, requested=requested, now=now)
                except RuntimeError:
                    lease = None
                if lease is not None and requested is not None and lease.address != requested:
                    self.store.release_lease(request.client_id)
                    lease = None
            elif requested is not None and lease.address != requested:
                lease = None
            if lease is None:
                return build_reply(request, DHCPNAK, self.server, None, self.subnet, self.lease_seconds)
            lease = self.store.activate_lease(request.client_id, self.lease_seconds, now=now)
            return build_reply(request, DHCPACK, self.server, lease, self.subnet, self.lease_seconds, self.dns)
        return None
