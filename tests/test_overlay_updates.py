import asyncio
import ipaddress
from types import SimpleNamespace
from unittest.mock import patch

from tcppeer.address_negotiation import SlaacLease
from tcppeer.client import Client
from tcppeer.protocol import parse_control
from tcppeer.server import Server


class FakeWriter:
    def __init__(self) -> None:
        self.data = bytearray()
        self.drains = 0

    def is_closing(self) -> bool:
        return False

    def write(self, data: bytes) -> None:
        self.data.extend(data)

    async def drain(self) -> None:
        self.drains += 1


class FakeTun:
    name = "tcppeer0"

    def __init__(self) -> None:
        self.removed = []
        self.configured = []

    def remove_address(self, address: str, prefix: int) -> None:
        self.removed.append((address, prefix))

    def configure(self, *values) -> None:
        self.configured.append(values)


def test_server_announces_effective_overlay_after_pd_change() -> None:
    server = Server.__new__(Server)
    server.config = SimpleNamespace(server_ipv4=ipaddress.IPv4Address("10.50.0.1"))
    server._active_server_ipv6 = ipaddress.IPv6Address("2001:db8:2::1")
    server._coordinator_writer = FakeWriter()

    asyncio.run(server._announce_overlay_update())

    message = parse_control(bytes(server._coordinator_writer.data))
    assert message.get("Action") == "Overlay-Update"
    assert message.get("Overlay-IPv6") == "2001:db8:2::1"


def test_client_replaces_stale_slaac_and_announces_it() -> None:
    client = Client.__new__(Client)
    client._overlay_ipv4 = ipaddress.IPv4Address("10.50.0.10")
    client._overlay_ipv4_prefix = 24
    client._overlay_ipv6 = ipaddress.IPv6Address("2001:db8:1::10")
    client._overlay_ipv6_prefix = 64
    client.tun = FakeTun()
    client._coordinator_writer = FakeWriter()
    client._remove_overlay_route = lambda address, prefix: None
    lease = SlaacLease(
        ipaddress.IPv6Address("2001:db8:2::10"),
        ipaddress.IPv6Network("2001:db8:2::/64"),
        (),
    )

    with patch("tcppeer.client.subprocess.run"):
        asyncio.run(client._apply_slaac_update(lease))

    assert client.tun.removed == [("2001:db8:1::10", 64)]
    assert client.tun.configured[-1] == ("10.50.0.10", 24, "2001:db8:2::10", 64)
    message = parse_control(bytes(client._coordinator_writer.data))
    assert message.get("Overlay-IPv6") == "2001:db8:2::10"
