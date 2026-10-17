import json
import ipaddress
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


def test_ipv6_isolation_only_blocks_overlay_and_host_addresses() -> None:
    firewall = ExitNodeFirewall(config(), "tcppeer0")
    firewall.ipv6_overlay_prefix = ipaddress.ip_network("2001:db8:100::/64")
    rules = firewall._ruleset(
        (), None,
        (ipaddress.ip_network("2001:db8:200::/64"),),
        (ipaddress.ip_address("2001:db8:200::10"),),
    )

    lan_to_overlay = (
        'iifname != "tcppeer0" ip6 saddr { 2001:db8:200::/64 } '
        'ip6 daddr 2001:db8:100::/64 counter drop'
    )
    assert rules.count(lan_to_overlay) == 2
    assert (
        'iifname "tcppeer0" ip6 daddr { 2001:db8:200::10 } counter drop'
    ) in rules
    assert 'iifname "tcppeer0" accept' in rules
    assert 'ip6 daddr 2001:db8:200::/64 counter drop' not in rules


def test_local_ipv6_discovery_excludes_tun_and_loopback() -> None:
    firewall = ExitNodeFirewall(config(), "tcppeer0")
    addresses = [
        {"ifname": "lo", "addr_info": [{"family": "inet6", "local": "::1", "prefixlen": 128}]},
        {"ifname": "tcppeer0", "addr_info": [{"family": "inet6", "local": "fdfe:cafe:cafe::1", "prefixlen": 64}]},
        {"ifname": "eth0", "addr_info": [
            {"family": "inet6", "local": "2001:db8:200::10", "prefixlen": 64},
            {"family": "inet6", "local": "fe80::10", "prefixlen": 64},
        ]},
    ]
    completed = Mock(stdout=json.dumps(addresses))
    with patch("tcppeer.exit_node.subprocess.run", return_value=completed):
        prefixes, local = firewall._local_ipv6_lan()

    assert prefixes == (
        ipaddress.ip_network("2001:db8:200::/64"),
        ipaddress.ip_network("fe80::/64"),
    )
    assert local == (
        ipaddress.ip_address("2001:db8:200::10"),
        ipaddress.ip_address("fe80::10"),
    )
