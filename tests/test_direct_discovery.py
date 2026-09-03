from pathlib import Path
from unittest.mock import patch

from tcppeer.server import discover_direct_ipv6


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
