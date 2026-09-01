from pathlib import Path

from tcppeer.coordinator_state import CoordinatorStore


def test_known_peer_survives_reopen(tmp_path: Path) -> None:
    path = tmp_path / "coordinator.db"
    store = CoordinatorStore(path)
    store.upsert({
        "network": "home", "peer_id": "phone", "role": "Client",
        "platform": "Android", "transport": "TCP6", "ipv4": "198.51.100.2",
        "ipv6": "2001:db8::2", "overlay_ipv4": "10.50.0.10",
        "overlay_ipv6": "fd00::10", "endpoint": "[2001:db8::2]:7444",
        "last_seen": 123,
    })
    store.close()

    reopened = CoordinatorStore(path)
    row = dict(reopened.load()[0])
    assert row["peer_id"] == "phone"
    assert row["overlay_ipv6"] == "fd00::10"
    assert reopened.delete("home", "phone")
    assert reopened.load() == []
    reopened.close()
