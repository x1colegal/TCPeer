import asyncio
import unittest
from unittest.mock import AsyncMock, MagicMock, patch

from tcppeer.config import CoordinatorConfig
from tcppeer.coordinator import Coordinator


class CoordinatorLivenessTests(unittest.IsolatedAsyncioTestCase):
    async def test_silent_authenticated_peer_is_marked_offline(self):
        config = CoordinatorConfig(
            listen_ipv4=None,
            listen_ipv6=None,
            port=7443,
            networks={"home": "secret"},
            state_db=self._state_path(),
            keepalive_seconds=0.01,
        )
        coordinator = Coordinator(config)
        reader = AsyncMock()
        writer = MagicMock()
        writer.drain = AsyncMock()
        writer.wait_closed = AsyncMock()
        writer.get_extra_info.return_value = ("2001:db8::10", 50000)
        writer.is_closing.return_value = False

        # AUTH, AUTH-PROOF, then both the normal read and liveness read time out.
        from tcppeer.protocol import ControlMessage
        messages = [
            ControlMessage("AUTH", {"Network": "home", "Peer-ID": "phone"}),
            ControlMessage("AUTH-PROOF", {"Proof": "invalid"}),
        ]

        # Authentication proof generation is outside this liveness regression;
        # accept it here and drive the connection into the authenticated loop.
        async def reads(*_args, **_kwargs):
            if messages:
                return messages.pop(0)
            await asyncio.Future()

        with (
            patch("tcppeer.coordinator.read_control", side_effect=reads),
            patch("tcppeer.coordinator.proof_matches", return_value=True),
            patch("tcppeer.coordinator.CONTROL_PONG_TIMEOUT_SECONDS", 0.01),
        ):
            await coordinator.handle_client(reader, writer)

        known = coordinator.known_peers[("home", "phone")]
        self.assertFalse(known.online)
        sent = b"".join(call.args[0] for call in writer.write.call_args_list)
        self.assertIn(b"TPCP/2 PING\r\n", sent)
        coordinator.store.close()

    def _state_path(self):
        self.addCleanup(self._cleanup_state)
        import tempfile
        self._tmp = tempfile.TemporaryDirectory()
        from pathlib import Path
        return Path(self._tmp.name) / "coordinator.db"

    def _cleanup_state(self):
        self._tmp.cleanup()
