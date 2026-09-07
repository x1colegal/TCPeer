import ipaddress
import unittest
from types import SimpleNamespace

from tcppeer.exit_node import ExitNodeFirewall


class ExitNodeInputTests(unittest.TestCase):
    def test_overlay_router_ipv6_is_reachable_only_from_tun(self):
        config = SimpleNamespace(
            exit_node_enabled=True,
            software_flow_offload=False,
            nat44=False,
            nat66=False,
        )
        firewall = ExitNodeFirewall(config, "tcppeer0")
        rules = firewall._ruleset(
            (), ipaddress.IPv6Address("2001:db8:1234::1"),
        )

        self.assertIn(
            'iifname != "tcppeer0" iifname != "lo" ip6 daddr 2001:db8:1234::1 counter drop',
            rules,
        )
        self.assertIn(
            'iifname "tcppeer0" meta l4proto { tcp, udp, icmp, ipv6-icmp } accept',
            rules,
        )


if __name__ == "__main__":
    unittest.main()
