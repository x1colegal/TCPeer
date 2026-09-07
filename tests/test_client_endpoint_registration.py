import unittest
from pathlib import Path
from unittest.mock import patch

from tcppeer.client import Client
from tcppeer.config import ClientConfig


class ClientEndpointRegistrationTests(unittest.TestCase):
    def test_auto_discovered_gua_is_not_assumed_public_before_observation(self):
        config = ClientConfig(
            coordinator_address="coordinator.example",
            coordinator_port=7443,
            network="home",
            peer_id="linux-client",
            secret="secret",
            target_peer="exit-node",
            state_db=Path("/tmp/tcppeer-client-endpoint-test.db"),
        )
        with (
            patch("tcppeer.client.discover_direct_ipv4", return_value="192.0.2.10"),
            patch("tcppeer.client.discover_direct_ipv6", return_value="2001:db8:1::10"),
            patch("tcppeer.client.discover_upstream_dns", return_value=()),
            patch.object(Client, "_read_default_route", return_value=None),
            patch("tcppeer.client.StateStore"),
        ):
            client = Client(config)

        self.assertEqual("2001:db8:1::10", client._direct_bind_ipv6)
        self.assertIsNone(client._registered_ipv6)
        self.assertIsNone(client._registered_port_ipv6)


if __name__ == "__main__":
    unittest.main()
