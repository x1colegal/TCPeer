import asyncio
import errno
import socket
from unittest import mock

from tcppeer.transport import DirectConnectionError, DirectConnector, Endpoint


def test_retry_window_ends_before_next_coordinator_punch_round() -> None:
    assert DirectConnector().retry_window == 12.0


def test_prebound_eaddrnotavail_is_retryable() -> None:
    error = OSError(errno.EADDRNOTAVAIL, "Cannot assign requested address")

    assert DirectConnector._retry_without_prebound(error)


def test_prebound_connection_refused_is_retryable() -> None:
    error = OSError(errno.ECONNREFUSED, "Connection refused")

    assert DirectConnector._retry_without_prebound(error)


def test_connection_refused_retry_is_short_and_jittered() -> None:
    error = OSError(errno.ECONNREFUSED, "Connection refused")

    delays = {DirectConnector._retry_delay(error) for _ in range(20)}

    assert all(0.015 <= delay <= 0.075 for delay in delays)
    assert len(delays) > 1


def test_connection_refused_retries_briefly_then_succeeds() -> None:
    attempts = 0
    fake_sock = mock.MagicMock()
    fake_sock.fileno.return_value = 42
    fake_sock.getsockname.return_value = ("::", 7449, 0, 0)
    fake_sock.getpeername.return_value = ("::1", 7449, 0, 0)

    async def fake_sock_connect(_sock, _addr):
        nonlocal attempts
        attempts += 1
        if attempts < 3:
            raise ConnectionRefusedError(errno.ECONNREFUSED, "Connection refused")

    async def run() -> None:
        loop = asyncio.get_running_loop()
        with (
            mock.patch.object(loop, "sock_connect", side_effect=fake_sock_connect),
            mock.patch("tcppeer.transport.asyncio.open_connection", return_value=(mock.MagicMock(), mock.MagicMock())),
            mock.patch("tcppeer.transport.asyncio.sleep", return_value=None),
        ):
            await DirectConnector(timeout=1.0, retry_window=12.0).connect(
                Endpoint("::", 7449, socket.AF_INET6),
                Endpoint("::1", 7449, socket.AF_INET6),
                socket.AF_INET6,
                prebound_socket=fake_sock,
            )

    with mock.patch("tcppeer.transport.socket.socket", return_value=fake_sock):
        asyncio.run(run())
    assert attempts == 3


def test_connection_refused_stops_after_grace_window() -> None:
    attempts = 0
    now = 100.0
    fake_sock = mock.MagicMock()
    fake_sock.fileno.return_value = 42
    fake_sock.getsockname.return_value = ("::", 7449, 0, 0)

    async def fake_sock_connect(_sock, _addr):
        nonlocal attempts, now
        attempts += 1
        now += 0.6
        raise ConnectionRefusedError(errno.ECONNREFUSED, "Connection refused")

    async def run() -> None:
        loop = asyncio.get_running_loop()
        with (
            mock.patch.object(loop, "time", side_effect=lambda: now),
            mock.patch.object(loop, "sock_connect", side_effect=fake_sock_connect),
            mock.patch("tcppeer.transport.asyncio.sleep", return_value=None),
        ):
            try:
                await DirectConnector(timeout=1.0, retry_window=12.0).connect(
                    Endpoint("::", 7449, socket.AF_INET6),
                    Endpoint("::1", 7449, socket.AF_INET6),
                    socket.AF_INET6,
                    prebound_socket=fake_sock,
                )
            except DirectConnectionError:
                return
            raise AssertionError("Expected DirectConnectionError")

    with mock.patch("tcppeer.transport.socket.socket", return_value=fake_sock):
        asyncio.run(run())
    assert attempts == 5
