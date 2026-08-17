from tcppeer.config import ServerConfig
from tcppeer.exit_node import ExitNodeFirewall
import json


def test_exit_node_rules_masquerade_all_subnets_arriving_from_tun():
    config = ServerConfig(
        coordinator_address="coordinator.example", coordinator_port=7443,
        network="home", peer_id="server", secret="cleartext",
        nat44=True, nat66=True,
    )
    rules = ExitNodeFirewall(config, "tcppeer0")._ruleset()
    assert "table inet tcppeer_input" in rules
    assert "meta l4proto { tcp, udp } accept" in rules
    assert "meta l4proto { icmp, ipv6-icmp } accept" in rules
    assert 'iifname "tcppeer0" accept' in rules
    assert rules.count('iifname "tcppeer0" oifname != "tcppeer0" masquerade') == 2
    assert "table ip tcppeer_nat44" in rules
    assert "table ip6 tcppeer_nat66" in rules


def test_exit_node_software_flowtable_uses_tun_and_upstream_devices():
    config = ServerConfig(
        coordinator_address="coordinator.example", coordinator_port=7443,
        network="home", peer_id="server", secret="secret",
        software_flow_offload=True,
    )
    rules = ExitNodeFirewall(config, "tcppeer0")._ruleset(("eth0", "wlan0"))
    assert "flowtable fastpath" in rules
    assert 'devices = { "tcppeer0", "eth0", "wlan0" };' in rules
    assert "flow add @fastpath" in rules


def test_server_input_rules_exist_without_exit_node_forwarding():
    config = ServerConfig(
        coordinator_address="coordinator.example", coordinator_port=7443,
        network="home", peer_id="server", secret="secret",
        exit_node_enabled=False,
    )
    rules = ExitNodeFirewall(config, "tcppeer0")._ruleset()
    assert "table inet tcppeer_input" in rules
    assert "meta l4proto { tcp, udp } accept" in rules
    assert "meta l4proto { icmp, ipv6-icmp } accept" in rules
    assert "tcppeer_forward" not in rules
    assert "masquerade" not in rules


def test_input_accepts_are_inserted_into_existing_filter_chain(monkeypatch):
    config = ServerConfig(
        coordinator_address="coordinator.example", coordinator_port=7443,
        network="home", peer_id="server", secret="secret",
    )
    calls = []

    def fake_run(command, **kwargs):
        calls.append((command, kwargs))
        if command[:4] == ("nft", "-j", "-a", "list"):
            payload = {"nftables": [
                {"chain": {"family": "ip", "table": "filter", "name": "INPUT", "type": "filter", "hook": "input", "prio": 0, "policy": "drop"}},
                {"chain": {"family": "ip6", "table": "raw", "name": "input", "type": "filter", "hook": "input", "prio": -300, "policy": "accept"}},
            ]}
            return subprocess.CompletedProcess(command, 0, json.dumps(payload), "")
        return subprocess.CompletedProcess(command, 0, "", "")

    import subprocess
    monkeypatch.setattr(subprocess, "run", fake_run)
    ExitNodeFirewall(config, "tcppeer0")._ensure_host_input_accepts()
    batch = calls[-1][1]["input"]
    assert "insert rule ip filter INPUT" in batch
    assert "tcp, udp, icmp" in batch
    assert "ip6 raw input" not in batch
