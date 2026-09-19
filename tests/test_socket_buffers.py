import tempfile
import unittest
from pathlib import Path

from tcppeer.server import configure_tcp_socket_buffer_limits


class SocketBufferLimitTests(unittest.TestCase):
    def test_socket_buffer_limits_raise_low_values(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "rmem_max").write_text("212992\n", encoding="ascii")
            (root / "wmem_max").write_text("4194304\n", encoding="ascii")

            configure_tcp_socket_buffer_limits(root)

            self.assertEqual((root / "rmem_max").read_text(encoding="ascii"), "8388608\n")
            self.assertEqual((root / "wmem_max").read_text(encoding="ascii"), "8388608\n")

    def test_socket_buffer_limits_preserve_higher_values(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "rmem_max").write_text("16777216\n", encoding="ascii")
            (root / "wmem_max").write_text("8388609\n", encoding="ascii")

            configure_tcp_socket_buffer_limits(root)

            self.assertEqual((root / "rmem_max").read_text(encoding="ascii"), "16777216\n")
            self.assertEqual((root / "wmem_max").read_text(encoding="ascii"), "8388609\n")
