import json
from pathlib import Path
from unittest.mock import Mock, patch

from tcppeer.config import ServerConfig
from tcppeer.exit_node import ExitNodeFirewall


def config() -> ServerConfig:
    return ServerConfig(
        coordinator_address="coordinator.example",
        coordinator_port=7443,
        network="home",
        peer_id="exit-node",
        secret="secret",
        state_db=Path("/tmp/tcppeer-exit-node-test.db"),
    )


def test_nat44_uses_kernel_selected_source() -> None:
    firewall = ExitNodeFirewall(config(), "tcppeer0")
    route = [{"dst": "192.0.2.1", "dev": "clat0", "prefsrc": "192.0.0.4"}]
    completed = Mock(stdout=json.dumps(route))
    with patch("tcppeer.exit_node.subprocess.run", return_value=completed):
        selected = firewall._selected_ipv4_route()

    assert selected == ("clat0", "192.0.0.4")
    rules = firewall._ruleset((), selected)
    nat44 = rules.split("table ip tcppeer_nat44", 1)[1].split("table ip6", 1)[0]
    assert 'iifname "tcppeer0" oifname "clat0" snat to 192.0.0.4' in rules
    assert 'iifname "tcppeer0" oifname != "tcppeer0" masquerade' not in nat44


def test_nat44_falls_back_to_masquerade_without_selected_route() -> None:
    rules = ExitNodeFirewall(config(), "tcppeer0")._ruleset((), None)
    assert 'iifname "tcppeer0" oifname != "tcppeer0" masquerade' in rules
