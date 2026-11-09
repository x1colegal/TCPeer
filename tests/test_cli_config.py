from pathlib import Path
import unittest

from tcppeer.cli import _format_endpoint, _load_config
from tcppeer.config import ClientConfig
from tcppeer.server import format_endpoint_address


class TestCliConfig(unittest.TestCase):
    def test_cli_format_endpoint(self) -> None:
        self.assertEqual(_format_endpoint("2804:18:1963:797f:90dc:ecd5:7d76:71a:7444"), "[2804:18:1963:797f:90dc:ecd5:7d76:71a]:7444")
        self.assertEqual(_format_endpoint("192.168.1.100:7444"), "192.168.1.100:7444")
        self.assertEqual(_format_endpoint("[2001:db8::1]:7444"), "[2001:db8::1]:7444")
        self.assertEqual(_format_endpoint("-"), "-")
        self.assertEqual(_format_endpoint(None), "-")
        self.assertEqual(format_endpoint_address("2804:18:1963:797f:90dc:ecd5:7d76:71a", 7444), "[2804:18:1963:797f:90dc:ecd5:7d76:71a]:7444")
        self.assertEqual(format_endpoint_address("192.168.1.100", 7444), "192.168.1.100:7444")


def test_cli_loads_linux_client_configuration(tmp_path: Path) -> None:
    path = tmp_path / "client.toml"
    path.write_text(
        """[coordinator]
address = "coordinator.example"
port = 7443
[identity]
network = "home"
peer_id = "linux-client"
secret = "secret"
[direct]
target_peer = "exit-node"
port = 7444
[interface]
name = "tcppeer0"
mtu = 1400
[routing]
use_exit_node = false
[paths]
state_db = "/tmp/tcppeer-client.db"
[runtime]
log_level = "INFO"
""",
        encoding="ascii",
    )
    config = _load_config(path)
    assert isinstance(config, ClientConfig)
    assert config.target_peer == "exit-node"
