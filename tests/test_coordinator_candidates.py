import asyncio

from tcppeer.coordinator import Coordinator, RegisteredPeer
from tcppeer.protocol import ControlMessage


class FakeWriter:
    def __init__(self) -> None:
        self.data = bytearray()

    def write(self, data: bytes) -> None:
        self.data.extend(data)

    async def drain(self) -> None:
        return None


def test_shared_usable_ipv6_prefix_does_not_require_same_observed_origin() -> None:
    assert Coordinator._can_use_local_candidates(
        "2000:dead:beef:0::10",
        "2000:dead:beef:0::20",
        6,
        same_public_origin=False,
    )


def test_link_local_ipv6_is_not_selected_as_unscoped_local_candidate() -> None:
    assert not Coordinator._can_use_local_candidates(
        "fe80::10",
        "fe80::20",
        6,
        same_public_origin=True,
    )


def test_private_ipv4_still_requires_same_observed_origin() -> None:
    assert not Coordinator._can_use_local_candidates(
        "192.168.83.10",
        "192.168.83.20",
        4,
        same_public_origin=False,
    )
    assert Coordinator._can_use_local_candidates(
        "192.168.83.10",
        "192.168.83.20",
        4,
        same_public_origin=True,
    )


def test_authenticated_peer_cannot_be_punched_before_register() -> None:
    async def scenario() -> None:
        coordinator = Coordinator.__new__(Coordinator)
        coordinator._punch_lock = asyncio.Lock()
        requester_writer = FakeWriter()
        target_writer = FakeWriter()
        requester = RegisteredPeer(
            "home", "laptop", requester_writer, "2001:db8::1", 50001,
            registered=True,
        )
        target = RegisteredPeer(
            "home", "phone", target_writer, "2001:db8::2", 50002,
            registered=False,
        )
        coordinator.peers = {
            ("home", "laptop"): requester,
            ("home", "phone"): target,
        }

        await coordinator.handle_message(
            requester,
            ControlMessage("PUNCH-READY", {"Peer-ID": "phone"}),
        )

        assert b"requested peer is unavailable" in requester_writer.data
        assert target_writer.data == b""

    asyncio.run(scenario())
