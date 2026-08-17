import asyncio
import socket

from tcppeer.config import CoordinatorConfig
from tcppeer.coordinator import Coordinator, KnownPeer, RegisteredPeer
from tcppeer.auth import authentication_proof
from tcppeer.protocol import ControlMessage, DATA_MAGIC, read_control


class MemoryWriter:
    def __init__(self):
        self.buffer = bytearray()

    def write(self, data):
        self.buffer.extend(data)

    async def drain(self):
        return None


def test_coordinator_auth_mapping_and_data_rejection():
    async def scenario():
        coordinator = Coordinator(CoordinatorConfig(
            listen_ipv4="127.0.0.1", listen_ipv6=None, port=0,
            networks={"home": "plain-secret"},
        ))
        await coordinator.start()
        port = coordinator.servers[0].sockets[0].getsockname()[1]
        reader, writer = await asyncio.open_connection("127.0.0.1", port)
        writer.write(ControlMessage("AUTH", {
            "Network": "home", "Peer-ID": "peer-a",
        }).encode())
        await writer.drain()
        challenge = await read_control(reader)
        assert challenge.command == "AUTH-CHALLENGE"
        writer.write(ControlMessage("AUTH-PROOF", {
            "Proof": authentication_proof("plain-secret", "home", "peer-a", challenge.get("Nonce")),
        }).encode())
        await writer.drain()
        assert (await read_control(reader)).command == "AUTH-OK"
        endpoint = await read_control(reader)
        assert endpoint.command == "ENDPOINT-INFO"
        assert endpoint.get("Address") == "127.0.0.1"
        writer.write(DATA_MAGIC + b"binary-data\r\n\r\n")
        await writer.drain()
        error = await read_control(reader)
        assert error.command == "ERROR"
        assert "forbidden" in error.get("Reason")
        writer.close()
        await writer.wait_closed()
        await coordinator.close()

    asyncio.run(scenario())


def test_endpoint_query_reports_observed_tcp_address_without_authentication():
    async def scenario():
        coordinator = Coordinator(CoordinatorConfig(
            listen_ipv4="127.0.0.1", listen_ipv6=None, port=0,
            networks={"home": "plain-secret"},
        ))
        await coordinator.start()
        port = coordinator.servers[0].sockets[0].getsockname()[1]
        reader, writer = await asyncio.open_connection("127.0.0.1", port)
        writer.write(ControlMessage("ENDPOINT-QUERY", {}).encode())
        await writer.drain()
        response = await read_control(reader)
        assert response.command == "ENDPOINT-INFO"
        assert response.get("Address") == "127.0.0.1"
        writer.close()
        await writer.wait_closed()
        await coordinator.close()

    asyncio.run(scenario())


def test_punch_go_uses_ipv6_only_when_both_peers_have_it():
    async def scenario():
        coordinator = Coordinator(CoordinatorConfig(networks={"home": "secret"}))
        left_writer = MemoryWriter()
        right_writer = MemoryWriter()
        left = RegisteredPeer("home", "left", left_writer, "2001:db8::10", 51000, declared_ipv6="2001:db8::10", listen_port=7444)
        right = RegisteredPeer(
            "home", "right", right_writer, "2001:db8::20", 52000,
            declared_ipv6="2001:db8::20", mapped_ipv6_port=45123, listen_port=7444,
        )
        await coordinator._punch_go(left, right)
        assert b"Family: IPv6" in left_writer.buffer
        assert b"Address: 2001:db8::20" in left_writer.buffer
        assert b"Port: 45123" in left_writer.buffer
        assert b"IPv4" not in left_writer.buffer

    asyncio.run(scenario())


def test_device_directory_includes_online_and_offline_peers():
    async def scenario():
        coordinator = Coordinator(CoordinatorConfig(networks={"home": "secret"}))
        writer = MemoryWriter()
        requester = RegisteredPeer("home", "phone", writer, "192.0.2.10", 50000)
        coordinator.known_peers[("home", "phone")] = KnownPeer(
            "home", "phone", role="Client", platform="Android",
            ipv4="192.0.2.10", transport="TCP4",
        )
        coordinator.known_peers[("home", "server")] = KnownPeer(
            "home", "server", online=False, role="Exit-Node", platform="Linux",
            ipv4="198.51.100.2", ipv6="2001:db8::2", transport="TCP6",
        )

        await coordinator._send_device_list(requester)

        assert b"Action: Device" in writer.buffer
        assert b"Peer-ID: server" in writer.buffer
        assert b"Online: no" in writer.buffer
        assert b"Role: Exit-Node" in writer.buffer
        assert b"Platform: Linux" in writer.buffer
        assert b"Transport: TCP6" in writer.buffer
        assert b"IPv6: 2001:db8::2" in writer.buffer
        assert writer.buffer.endswith(b"Action: List-End\r\n\r\n")

    asyncio.run(scenario())


def test_same_local_network_prefers_host_candidate_scope():
    assert Coordinator._same_local_network("fd73:cafe:cafe::10", "fd73:cafe:cafe::20", 6)
    assert not Coordinator._same_local_network("fd73:cafe:cafe::10", "fd74:cafe:cafe::20", 6)
    assert Coordinator._same_local_network("192.168.66.101", "192.168.196.254", 4)


def test_same_ula_is_used_only_behind_same_observed_public_address():
    async def scenario():
        coordinator = Coordinator(CoordinatorConfig(networks={"home": "secret"}))
        left_writer = MemoryWriter()
        right_writer = MemoryWriter()
        left = RegisteredPeer(
            "home", "left", left_writer, "198.51.100.10", 51000,
            local_ipv6="fd00::10", listen_port=7444,
        )
        right = RegisteredPeer(
            "home", "right", right_writer, "203.0.113.20", 52000,
            local_ipv6="fd00::20", listen_port=7444,
        )
        await coordinator._punch_go(left, right)
        assert b"Family: IPv4" in left_writer.buffer
        assert b"Address: fd00::" not in left_writer.buffer

    asyncio.run(scenario())


def test_linux_peernet_host_can_delete_and_revoke_client(tmp_path):
    async def scenario():
        coordinator = Coordinator(CoordinatorConfig(
            networks={"home": "secret"}, peernet_hosting=True,
            hosting_peer_ids=("main-server",), hosting_state_db=tmp_path / "hosting.db",
        ))
        writer = MemoryWriter()
        host = RegisteredPeer(
            "home", "main-server", writer, "2001:db8::1", 7444,
            platform="Linux", peernet_hosting=True,
        )
        coordinator.known_peers[("home", "phone")] = KnownPeer("home", "phone")
        await coordinator._delete_client(host, "phone")
        assert ("home", "phone") not in coordinator.known_peers
        assert coordinator.hosting_store.is_revoked("home", "phone")
        assert b"Action: Delete-OK" in writer.buffer
        await coordinator.close()

    asyncio.run(scenario())
