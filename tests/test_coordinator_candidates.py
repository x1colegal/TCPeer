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

    def get_extra_info(self, name: str):
        return ("198.51.100.20", 45678) if name == "peername" else None

    def close(self) -> None:
        return None

    async def wait_closed(self) -> None:
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


def test_punch_go_sends_remote_edm_candidates() -> None:
    async def scenario() -> None:
        coordinator = Coordinator.__new__(Coordinator)
        coordinator._active_direct_sessions = {}
        coordinator.known_peers = {}
        left_writer = FakeWriter()
        right_writer = FakeWriter()
        left = RegisteredPeer(
            "home", "left", left_writer, "2606:4700::10", 50001,
            declared_ipv6="2606:4700::10", mapped_ipv6_port=50001,
            edm_ipv6_ports=(50002, 50003), registered=True,
        )
        right = RegisteredPeer(
            "home", "right", right_writer, "2606:4700::20", 51001,
            declared_ipv6="2606:4700::20", mapped_ipv6_port=51001,
            edm_ipv6_ports=(51002, 51003), registered=True,
        )

        await coordinator._punch_go(left, right)

        assert b"Port-Guesses: 51002,51003\r\n" in left_writer.data
        assert b"Local-EDM: yes\r\n" in left_writer.data
        assert b"Port-Guesses: 50002,50003\r\n" in right_writer.data
        assert b"Local-EDM: yes\r\n" in right_writer.data

    asyncio.run(scenario())


def test_edm_probe_listener_only_reflects_endpoint_queries() -> None:
    async def scenario() -> None:
        coordinator = Coordinator.__new__(Coordinator)
        coordinator.config = type("Config", (), {"max_message_size": 4096})()
        reader = asyncio.StreamReader()
        reader.feed_data(ControlMessage("ENDPOINT-QUERY", {}).encode())
        reader.feed_eof()
        writer = FakeWriter()

        await coordinator.handle_endpoint_probe(reader, writer)

        assert b"TPCP/2 ENDPOINT-INFO\r\n" in writer.data
        assert b"Address: 198.51.100.20\r\n" in writer.data
        assert b"Port: 45678\r\n" in writer.data

    asyncio.run(scenario())
