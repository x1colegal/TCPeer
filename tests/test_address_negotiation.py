import asyncio
from types import SimpleNamespace

from tcppeer.address_negotiation import dhcp_discover, router_solicitation
from tcppeer.client import Client
from tcppeer.dhcp import DHCPDISCOVER
from tcppeer.packet import extract_dhcp_payload
from tcppeer.dhcp import parse_message
from tcppeer.ra import is_router_solicitation


def test_linux_client_builds_raw_dhcp_discover() -> None:
    packet = dhcp_discover("linux-client", 0x12345678)
    message = parse_message(extract_dhcp_payload(packet))
    assert message.xid == 0x12345678
    assert message.message_type == DHCPDISCOVER


def test_linux_client_builds_router_solicitation() -> None:
    assert is_router_solicitation(router_solicitation())


def test_primary_disconnect_requires_fresh_address_negotiation() -> None:
    client = Client.__new__(Client)
    client.config = SimpleNamespace(target_peer="exit-node")
    client._configured = asyncio.Event()
    client._configured.set()

    asyncio.run(client._after_direct_data("mesh-peer"))
    assert client._configured.is_set()

    asyncio.run(client._after_direct_data("exit-node"))
    assert not client._configured.is_set()
