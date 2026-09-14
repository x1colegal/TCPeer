import asyncio
from pathlib import Path
from types import SimpleNamespace

from tcppeer.config import CoordinatorConfig
from tcppeer.coordinator import Coordinator, KnownPeer
from tcppeer.coordinator_state import CoordinatorStore


def test_known_peer_survives_reopen(tmp_path: Path) -> None:
    path = tmp_path / "coordinator.db"
    store = CoordinatorStore(path)
    store.upsert({
        "network": "home", "peer_id": "phone", "display_name": "My phone", "role": "Client",
        "platform": "Android", "transport": "TCP6", "ipv4": "198.51.100.2",
        "ipv6": "2001:db8::2", "overlay_ipv4": "10.50.0.10",
        "overlay_ipv6": "fd00::10", "endpoint": "[2001:db8::2]:7444",
        "last_seen": 123,
    })
    store.close()

    reopened = CoordinatorStore(path)
    row = dict(reopened.load()[0])
    assert row["peer_id"] == "phone"
    assert row["display_name"] == "My phone"
    assert row["overlay_ipv6"] == "fd00::10"
    assert reopened.delete("home", "phone")
    assert reopened.load() == []
    reopened.close()


def test_offline_device_list_hides_physical_addresses_and_transport(tmp_path: Path) -> None:
    class Writer:
        def __init__(self) -> None:
            self.data = bytearray()

        def write(self, data: bytes) -> None:
            self.data.extend(data)

        async def drain(self) -> None:
            return None

    config = CoordinatorConfig(
        listen_ipv4=None,
        listen_ipv6=None,
        port=7443,
        networks={"home": "secret"},
        state_db=tmp_path / "coordinator.db",
    )
    coordinator = Coordinator(config)
    coordinator.known_peers[("home", "phone")] = KnownPeer(
        network="home",
        peer_id="phone",
        online=False,
        transport="TCP6",
        ipv4="198.51.100.2",
        ipv6="2001:db8::2",
        overlay_ipv4="10.50.0.10",
        overlay_ipv6="fd00::10",
    )
    writer = Writer()

    asyncio.run(coordinator._send_device_list(SimpleNamespace(network="home", writer=writer)))
    message = bytes(writer.data)

    assert b"Online: no\r\n" in message
    assert b"Transport: -\r\n" in message
    assert b"IPv4: \r\n" in message
    assert b"IPv6: \r\n" in message
    assert b"Overlay-IPv4: \r\n" in message
    assert b"Overlay-IPv6: \r\n" in message
    assert b"198.51.100.2" not in message
    assert b"2001:db8::2" not in message
    assert b"10.50.0.10" not in message
    assert b"fd00::10" not in message
    assert coordinator.known_peers[("home", "phone")].ipv6 == "2001:db8::2"
    assert coordinator.known_peers[("home", "phone")].overlay_ipv6 == "fd00::10"
    coordinator.store.close()
