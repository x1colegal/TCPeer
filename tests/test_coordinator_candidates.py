import asyncio

from tcppeer.config import CoordinatorConfig
from tcppeer.coordinator import Coordinator, RegisteredPeer


class Writer:
    def __init__(self) -> None:
        self.messages: list[bytes] = []

    def write(self, payload: bytes) -> None:
        self.messages.append(payload)

    async def drain(self) -> None:
        pass


def peer(peer_id: str, observed: str, local: str) -> RegisteredPeer:
    return RegisteredPeer(
        network="home", peer_id=peer_id, writer=Writer(),
        observed_address=observed, observed_port=50000,
        declared_ipv6=local, mapped_ipv6_port=50000,
        local_ipv6=local, listen_port=7444,
    )


def test_ipv6_same_lan_uses_local_candidates_without_same_observed_address(tmp_path) -> None:
    coordinator = Coordinator(CoordinatorConfig(
        networks={"home": "secret"}, state_db=tmp_path / "coordinator.db",
    ))
    left = peer("left", "2001:db8:ffff::1", "2001:db8:1::10")
    right = peer("right", "2001:db8:eeee::1", "2001:db8:1::20")

    asyncio.run(coordinator._punch_go(left, right))

    left_message = left.writer.messages[-1].decode("ascii")
    right_message = right.writer.messages[-1].decode("ascii")
    assert "Address: 2001:db8:1::20" in left_message
    assert "Address: 2001:db8:1::10" in right_message
    assert "Port: 7444" in left_message
    assert "Traversal: Simultaneous-Open" in left_message
    coordinator.store.close()
