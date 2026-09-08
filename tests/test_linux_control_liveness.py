import asyncio
import unittest
from unittest.mock import AsyncMock, MagicMock, patch

from tcppeer.server import Server


class LinuxControlLivenessTests(unittest.IsolatedAsyncioTestCase):
    async def test_unanswered_keepalive_breaks_blackholed_connection(self):
        server = object.__new__(Server)
        reader = MagicMock()
        writer = MagicMock()
        writer.drain = AsyncMock()

        async def never_returns(*_args, **_kwargs):
            await asyncio.Future()

        with (
            patch("tcppeer.server.read_control", side_effect=never_returns),
            patch("tcppeer.server.CONTROL_IDLE_SECONDS", 0.01),
            patch("tcppeer.server.CONTROL_REPLY_TIMEOUT_SECONDS", 0.01),
        ):
            with self.assertRaisesRegex(ConnectionError, "did not answer keepalive"):
                await server._read_control_alive(reader, writer)

        sent = b"".join(call.args[0] for call in writer.write.call_args_list)
        self.assertIn(b"TPCP/2 KEEPALIVE\r\n", sent)
        writer.drain.assert_awaited_once()


if __name__ == "__main__":
    unittest.main()
