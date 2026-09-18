import asyncio
from types import SimpleNamespace
from unittest.mock import patch

from tcppeer.server import Server


def server_with_tun() -> Server:
    server = Server.__new__(Server)
    server.tun = SimpleNamespace(fd=42)
    server._tun_receive_queue = asyncio.Queue(maxsize=4)
    return server


def test_writable_tun_bypasses_receive_queue() -> None:
    server = server_with_tun()
    packet = b"packet"
    with patch("tcppeer.server.os.write", return_value=len(packet)) as write:
        server._queue_tun_packet("peer", packet)
    write.assert_called_once_with(42, packet)
    assert server._tun_receive_queue.empty()


def test_busy_tun_uses_receive_queue() -> None:
    server = server_with_tun()
    packet = b"packet"
    with patch("tcppeer.server.os.write", side_effect=BlockingIOError):
        server._queue_tun_packet("peer", packet)
    assert server._tun_receive_queue.get_nowait() == ("peer", packet)
