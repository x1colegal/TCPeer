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


def test_punch_success_releases_busy_lease_and_wakes_deferred() -> None:
    async def scenario() -> None:
        coordinator = Coordinator.__new__(Coordinator)
        coordinator._punch_lock = asyncio.Lock()
        coordinator._punch_busy_until = {
            ("home", "p1"): 999999.0,
            ("home", "p2"): 999999.0,
        }
        coordinator._active_direct_sessions = {}
        coordinator._deferred_punch_tasks = {}
        writer1 = FakeWriter()
        writer2 = FakeWriter()
        writer3 = FakeWriter()
        p1 = RegisteredPeer("home", "p1", writer1, "2001:db8::1", 50001, registered=True, connected_at=100.0)
        p2 = RegisteredPeer("home", "p2", writer2, "2001:db8::2", 50002, registered=True, connected_at=200.0)
        p3 = RegisteredPeer("home", "p3", writer3, "2001:db8::3", 50003, registered=True, connected_at=300.0)
        coordinator.peers = {
            ("home", "p1"): p1,
            ("home", "p2"): p2,
            ("home", "p3"): p3,
        }
        p1.ready_for.add("p3")
        p3.ready_for.add("p1")

        # Fake a deferred punch between p1 and p3 sleeping for 13s
        pair = ("home", "p1", "p3")
        sessions = coordinator._pair_sessions(p1, p3)
        dummy_task = asyncio.create_task(asyncio.sleep(13.0))
        coordinator._deferred_punch_tasks[pair] = dummy_task

        # Now p1 reports PUNCH-SUCCESS with p2
        await coordinator.handle_message(
            p1,
            ControlMessage("PUNCH-SUCCESS", {"Peer-ID": "p2"}),
        )

        assert ("home", "p1") not in coordinator._punch_busy_until
        assert ("home", "p2") not in coordinator._punch_busy_until
        assert coordinator._pair_key(p1, p2) in coordinator._active_direct_sessions
        await asyncio.sleep(0)
        assert dummy_task.cancelled()
        # New task was scheduled with immediate delay
        new_task = coordinator._deferred_punch_tasks[pair]
        assert new_task is not dummy_task
        new_task.cancel()
        await asyncio.gather(new_task, return_exceptions=True)

    asyncio.run(scenario())
