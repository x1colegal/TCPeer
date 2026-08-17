import asyncio
import socket

from tcppeer.transport import choose_family, is_usable_ipv6, resolve_tcp_endpoints
from tcppeer.server import public_address


def test_link_local_alone_is_not_usable_ipv6():
    assert not is_usable_ipv6("fe80::1")
    assert not is_usable_ipv6("::1")
    assert not is_usable_ipv6("::ffff:192.0.2.1")
    assert is_usable_ipv6("2001:db8::1")
    assert is_usable_ipv6("fd00::1")


def test_private_and_ula_bind_addresses_are_not_advertised_as_public():
    assert public_address("192.168.1.10") is None
    assert public_address("fd00::10") is None
    assert public_address("8.8.8.8") == "8.8.8.8"
    assert public_address("2606:4700:4700::1111") == "2606:4700:4700::1111"


def test_both_usable_ipv6_requires_tcp6():
    assert choose_family(["2001:db8::1"], ["fd00::2"]) == socket.AF_INET6


def test_tcp4_only_when_one_peer_lacks_usable_ipv6():
    assert choose_family([], ["2001:db8::2"]) == socket.AF_INET
    assert choose_family(["fe80::1"], ["2001:db8::2"]) == socket.AF_INET
    assert choose_family(["2001:db8::1"], []) == socket.AF_INET


def test_connector_has_no_fallback_path():
    source = open("src/tcppeer/transport.py", encoding="utf-8").read()
    connect_body = source.split("async def connect", 1)[1]
    assert "required_family" in connect_body
    assert "getaddrinfo" not in connect_body


def test_coordinator_address_accepts_dns_name():
    endpoints = asyncio.run(resolve_tcp_endpoints("localhost", 7443))
    assert endpoints
    assert all(item[1] == socket.SOCK_STREAM for item in endpoints)
    assert all(item[2] == socket.IPPROTO_TCP for item in endpoints)


def test_coordinator_address_accepts_bracketed_ipv6():
    endpoints = asyncio.run(resolve_tcp_endpoints("[::1]", 7443))
    assert any(item[0] == socket.AF_INET6 for item in endpoints)
