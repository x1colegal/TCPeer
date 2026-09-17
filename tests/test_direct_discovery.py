from pathlib import Path
from unittest.mock import patch

from tcppeer.server import discover_direct_ipv4, discover_direct_ipv6


class RouteProbe:
    def __init__(self, selected: str) -> None:
        self.selected = selected
        self.destination = None
        self.closed = False

    def connect(self, destination) -> None:
        self.destination = destination

    def getsockname(self):
        return self.selected, 49152

    def close(self) -> None:
        self.closed = True


def test_discover_direct_ipv4_uses_kernel_selected_clat_source() -> None:
    probe = RouteProbe("192.0.0.4")
    with patch("socket.socket", return_value=probe):
        assert discover_direct_ipv4({"tcppeer0"}) == "192.0.0.4"

    assert probe.destination == ("192.0.2.1", 9)
    assert probe.closed


def test_discover_direct_ipv6_ignores_tentative_and_overlay_interfaces() -> None:
    rows = "\n".join((
        "20010db8000000000000000000000010 02 40 00 40 wlan0",
        "fd7a115ca1e000000000000000000001 03 80 00 80 tailscale0",
        "20010db8000000000000000000000020 02 40 00 80 wlan0",
    ))
    with patch.object(Path, "read_text", return_value=rows), patch(
        "socket.if_nameindex", return_value=[(2, "wlan0"), (3, "tailscale0")],
    ):
        assert discover_direct_ipv6() == "2001:db8::20"
