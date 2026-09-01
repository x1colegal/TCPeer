from pathlib import Path

from tcppeer.cli import _load_config
from tcppeer.config import ClientConfig


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
