"""Minimal DHCPv6 Prefix Delegation client for TCPeer."""

from __future__ import annotations

import asyncio
from dataclasses import dataclass
import ipaddress
import logging
import os
import socket
import struct
import subprocess

LOG = logging.getLogger("tcppeer.pd")

CLIENT_PORT = 546
SERVER_PORT = 547

SOLICIT = 1
ADVERTISE = 2
REQUEST = 3
REPLY = 7

OPT_CLIENTID = 1
OPT_SERVERID = 2
OPT_ORO = 6
OPT_ELAPSED_TIME = 8
OPT_IA_PD = 25
OPT_IAPREFIX = 26

ALL_DHCP_RELAY_AGENTS_AND_SERVERS = "ff02::1:2"


@dataclass(frozen=True)
class DelegatedPrefix:
    prefix: ipaddress.IPv6Network
    preferred_lifetime: int
    valid_lifetime: int
    server_id: bytes


def discover_ipv6_upstream(excluded: set[str]) -> str | None:
    try:
        result = subprocess.run(
            ("ip", "-6", "route", "show", "default"),
            check=False,
            capture_output=True,
            text=True,
        )
    except OSError:
        return None

    for line in result.stdout.splitlines():
        fields = line.split()
        if "dev" not in fields:
            continue
        index = fields.index("dev") + 1
        if index >= len(fields):
            continue
        interface = fields[index]
        if interface not in excluded:
            return interface

    return None


def _option(code: int, payload: bytes) -> bytes:
    return struct.pack("!HH", code, len(payload)) + payload


def _duid() -> bytes:
    # DUID-EN with a TCPeer private enterprise identifier.
    # Persistence comes from machine-id.
    try:
        machine_id = open("/etc/machine-id", "rb").read().strip()
    except OSError:
        machine_id = os.urandom(16).hex().encode()

    identifier = machine_id[:32]
    return struct.pack("!HI", 2, 0x54505044) + identifier


def _ia_pd(iaid: int) -> bytes:
    return struct.pack("!III", iaid, 0, 0)


def _message(kind: int, transaction: bytes, duid: bytes, iaid: int,
             server_id: bytes | None = None) -> bytes:
    body = _option(OPT_CLIENTID, duid)
    if server_id is not None:
        body += _option(OPT_SERVERID, server_id)
    body += _option(OPT_ELAPSED_TIME, b"\x00\x00")
    body += _option(OPT_IA_PD, _ia_pd(iaid))
    return bytes((kind,)) + transaction + body


def _options(data: bytes):
    offset = 0
    while offset + 4 <= len(data):
        code, length = struct.unpack_from("!HH", data, offset)
        offset += 4
        end = offset + length
        if end > len(data):
            break
        yield code, data[offset:end]
        offset = end


def _parse_pd(packet: bytes, transaction: bytes) -> DelegatedPrefix | None:
    if len(packet) < 4 or packet[1:4] != transaction:
        return None
    if packet[0] not in (ADVERTISE, REPLY):
        return None

    server_id = None
    ia_pd = None

    for code, payload in _options(packet[4:]):
        if code == OPT_SERVERID:
            server_id = payload
        elif code == OPT_IA_PD:
            ia_pd = payload

    if server_id is None or ia_pd is None or len(ia_pd) < 12:
        return None

    for code, payload in _options(ia_pd[12:]):
        if code != OPT_IAPREFIX or len(payload) < 25:
            continue

        preferred, valid, prefix_length = struct.unpack_from("!IIB", payload, 0)
        prefix_address = ipaddress.IPv6Address(payload[9:25])

        try:
            prefix = ipaddress.ip_network(
                f"{prefix_address}/{prefix_length}",
                strict=False,
            )
        except ValueError:
            continue

        return DelegatedPrefix(
            prefix=prefix,
            preferred_lifetime=preferred,
            valid_lifetime=valid,
            server_id=server_id,
        )

    return None


class PrefixDelegationClient:
    def __init__(self, interface: str):
        self.interface = interface
        self.duid = _duid()
        self.iaid = socket.if_nametoindex(interface)

    async def acquire(self, timeout: float = 5.0) -> DelegatedPrefix | None:
        loop = asyncio.get_running_loop()
        sock = socket.socket(socket.AF_INET6, socket.SOCK_DGRAM)
        sock.setblocking(False)

        try:
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            sock.setsockopt(
                socket.SOL_SOCKET,
                socket.SO_BINDTODEVICE,
                self.interface.encode() + b"\0",
            )
            sock.bind(("::", CLIENT_PORT))

            destination = (
                ALL_DHCP_RELAY_AGENTS_AND_SERVERS,
                SERVER_PORT,
                0,
                socket.if_nametoindex(self.interface),
            )

            transaction = os.urandom(3)
            solicit = _message(SOLICIT, transaction, self.duid, self.iaid)

            await loop.sock_sendto(sock, solicit, destination)

            try:
                packet, _ = await asyncio.wait_for(
                    loop.sock_recvfrom(sock, 65535),
                    timeout,
                )
            except asyncio.TimeoutError:
                return None

            advertised = _parse_pd(packet, transaction)
            if advertised is None:
                return None

            transaction = os.urandom(3)
            request = _message(
                REQUEST,
                transaction,
                self.duid,
                self.iaid,
                advertised.server_id,
            )
            await loop.sock_sendto(sock, request, destination)

            try:
                packet, _ = await asyncio.wait_for(
                    loop.sock_recvfrom(sock, 65535),
                    timeout,
                )
            except asyncio.TimeoutError:
                return None

            delegated = _parse_pd(packet, transaction)
            if delegated is None or packet[0] != REPLY:
                return None

            LOG.info(
                "DHCPv6-PD acquired %s from upstream interface %s",
                delegated.prefix,
                self.interface,
            )
            return delegated

        except (OSError, ValueError) as exc:
            LOG.warning(
                "DHCPv6-PD failed on %s: %s",
                self.interface,
                exc,
            )
            return None
        finally:
            sock.close()


def slaac_subnet(prefix: ipaddress.IPv6Network) -> ipaddress.IPv6Network | None:
    """Take the first /64 from a delegated prefix."""
    if prefix.prefixlen > 64:
        return None
    if prefix.prefixlen == 64:
        return prefix
    return next(prefix.subnets(new_prefix=64))


def router_address(prefix: ipaddress.IPv6Network) -> ipaddress.IPv6Address:
    return ipaddress.IPv6Address(int(prefix.network_address) + 1)
