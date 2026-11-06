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
        None,
        "2000:dead:beef:0::20",
        None,
        6,
    )


def test_link_local_ipv6_is_not_selected_as_unscoped_local_candidate() -> None:
    assert not Coordinator._can_use_local_candidates(
        "fe80::10",
        None,
        "fe80::20",
        None,
        6,
    )


def test_private_ipv4_uses_matching_reported_interface_prefix() -> None:
    assert Coordinator._can_use_local_candidates(
        "192.168.83.10",
        24,
        "192.168.83.20",
        24,
        4,
    )
    assert not Coordinator._can_use_local_candidates(
        "192.168.83.10",
        24,
        "192.168.84.20",
        24,
        4,
    )


def test_mismatched_ipv4_prefixes_use_public_candidates_even_in_same_octets() -> None:
    assert not Coordinator._can_use_local_candidates(
        "192.168.83.10",
        16,
        "192.168.83.20",
        24,
        4,
    )
    assert not Coordinator._can_use_local_candidates(
        "192.168.84.10",
        16,
        "192.168.83.20",
        24,
        4,
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
