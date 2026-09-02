import asyncio
import socket
from types import SimpleNamespace

from tcppeer.server import Server


def test_duplicate_can_win_before_data_by_deterministic_key() -> None:
    server = Server.__new__(Server)
    server._direct_owner_committed = set()

    assert server._incoming_direct_wins("phone", ("z", "z"), ("a", "a"))
    assert not server._incoming_direct_wins("phone", ("a", "a"), ("z", "z"))


def test_late_duplicate_cannot_replace_connection_carrying_data() -> None:
    server = Server.__new__(Server)
    server._direct_owner_committed = {"phone"}

    assert not server._incoming_direct_wins("phone", ("z", "z"), ("a", "a"))


def test_auto_discovered_ipv4_gets_a_passive_listener(monkeypatch) -> None:
    server = Server.__new__(Server)
    server.config = SimpleNamespace(direct_port=7444)
    server._direct_bind_ipv6 = "2001:db8::10"
    server._direct_bind_ipv4 = "192.0.2.10"
    server._listeners = []
    calls = []

    async def fake_start_server(callback, address, port, *, family, reuse_port):
        calls.append((address, port, family, reuse_port))
        return object()

    monkeypatch.setattr(asyncio, "start_server", fake_start_server)
    asyncio.run(server._start_direct_listeners())

    assert calls == [
        ("2001:db8::10", 7444, socket.AF_INET6, True),
        ("192.0.2.10", 7444, socket.AF_INET, True),
    ]
