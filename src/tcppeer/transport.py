"""Direct TCP address-family policy and connection helpers."""

from __future__ import annotations

from dataclasses import dataclass
import asyncio
import ipaddress
import socket
from typing import Iterable


class DirectConnectionError(ConnectionError):
    """Raised when the mandatory direct connection cannot be established."""


def is_usable_ipv6(address: str | None) -> bool:
    if not address:
        return False
    try:
        parsed = ipaddress.ip_address(address.split("%", 1)[0])
    except ValueError:
        return False
    return isinstance(parsed, ipaddress.IPv6Address) and not (
        parsed.is_unspecified
        or parsed.is_loopback
        or parsed.is_multicast
        or parsed.is_link_local
        or parsed.ipv4_mapped is not None
    )


def choose_family(local_ipv6: Iterable[str], remote_ipv6: Iterable[str]) -> socket.AddressFamily:
    local_has_ipv6 = any(is_usable_ipv6(item) for item in local_ipv6)
    remote_has_ipv6 = any(is_usable_ipv6(item) for item in remote_ipv6)
    return socket.AF_INET6 if local_has_ipv6 and remote_has_ipv6 else socket.AF_INET


async def resolve_tcp_endpoints(host: str, port: int):
    """Resolve a coordinator DNS name or literal IP for TCP connections."""
    normalized = host.strip()
    if normalized.startswith("[") and normalized.endswith("]"):
        normalized = normalized[1:-1]
    if not normalized:
        raise DirectConnectionError("coordinator DNS name or IP address is empty")
    loop = asyncio.get_running_loop()
    try:
        return await loop.getaddrinfo(
            normalized,
            port,
            family=socket.AF_UNSPEC,
            type=socket.SOCK_STREAM,
            proto=socket.IPPROTO_TCP,
        )
    except socket.gaierror as exc:
        raise DirectConnectionError(f"cannot resolve coordinator DNS name: {host}") from exc


@dataclass(frozen=True)
class Endpoint:
    address: str
    port: int
    family: socket.AddressFamily


class DirectConnector:
    """Attempt exactly one policy-approved direct TCP family."""

    def __init__(self, timeout: float = 10.0):
        self.timeout = timeout

    async def connect(
        self,
        local: Endpoint,
        remote: Endpoint,
        required_family: socket.AddressFamily,
        prebound_socket: socket.socket | None = None,
    ):
        if local.family != required_family or remote.family != required_family:
            raise DirectConnectionError("endpoint family violates transport policy")
        loop = asyncio.get_running_loop()
        deadline = loop.time() + 60.0
        last_exc = None

        while loop.time() < deadline:
            sock = prebound_socket or socket.socket(required_family, socket.SOCK_STREAM, socket.IPPROTO_TCP)
            sock.setblocking(False)
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_SNDBUF, 4 * 1024 * 1024)
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 4 * 1024 * 1024)
            if hasattr(socket, "SO_REUSEPORT"):
                sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEPORT, 1)

            try:
                if prebound_socket is None:
                    sock.bind((local.address, local.port))

                remaining = deadline - loop.time()
                attempt_timeout = min(self.timeout, remaining)
                await asyncio.wait_for(
                    loop.sock_connect(sock, (remote.address, remote.port)),
                    attempt_timeout,
                )

                reader, writer = await asyncio.open_connection(sock=sock)
                return reader, writer

            except Exception as exc:
                last_exc = exc
                sock.close()

                if prebound_socket is not None:
                    break

                await asyncio.sleep(0.1)

        family_name = "TCP6" if required_family == socket.AF_INET6 else "TCP4"
        raise DirectConnectionError(
            f"{family_name} direct connection failed after retry window"
        ) from last_exc

    # No alternate-family attempt exists here by design. Callers must treat an
    # exception as final when IPv6 was selected.
