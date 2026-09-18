from tcppeer.protocol import ControlMessage
from tcppeer.server import Server
from tcppeer.state import StateStore


def test_directory_refresh_preserves_active_direct_endpoint(tmp_path) -> None:
    server = Server.__new__(Server)
    server.store = StateStore(tmp_path / "state.db")
    server.direct_writers = {"phone": object()}
    server.store.update_peer(
        "phone", transport="TCP6 Direct", endpoint="[2001:db8:1::20]:7444",
    )

    server._update_peer_from_directory("phone", ControlMessage("PEER-INFO", {
        "Overlay-IPv4": "10.50.0.10",
        "Overlay-IPv6": "fd00::10",
        "Transport": "TCP6",
        "Endpoint": "[2001:db8:ffff::20]:50000",
    }))

    row = server.store.connection.execute(
        "SELECT transport, endpoint FROM peers WHERE peer_id='phone'",
    ).fetchone()
    assert tuple(row) == ("TCP6 Direct", "[2001:db8:1::20]:7444")
    ipv4 = bytearray(20)
    ipv4[0] = 0x45
    ipv4[16:20] = bytes((10, 50, 0, 10))
    ipv6 = bytearray(40)
    ipv6[0] = 0x60
    ipv6[24:40] = bytes.fromhex("fd000000000000000000000000000010")
    assert server._peer_for_packet(bytes(ipv4)) == "phone"
    assert server._peer_for_packet(bytes(ipv6)) == "phone"
    server.store.close()
