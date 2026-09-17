import unittest
import socket
from pathlib import Path
from unittest.mock import patch

from tcppeer.client import Client, _can_bind_local_address
from tcppeer.config import ClientConfig


class ClientEndpointRegistrationTests(unittest.TestCase):
    def test_local_address_probe_closes_socket_and_reports_bind_result(self):
        probe = unittest.mock.Mock()
        with patch("tcppeer.client.socket.socket", return_value=probe):
            self.assertTrue(_can_bind_local_address("192.0.2.10", socket.AF_INET))
        probe.bind.assert_called_once_with(("192.0.2.10", 0))
        probe.close.assert_called_once_with()

        rejected = unittest.mock.Mock()
        rejected.bind.side_effect = OSError("address is no longer local")
        with patch("tcppeer.client.socket.socket", return_value=rejected):
            self.assertFalse(_can_bind_local_address("192.0.2.10", socket.AF_INET))
        rejected.close.assert_called_once_with()

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
