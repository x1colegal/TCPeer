import asyncio
import ipaddress
import struct
from types import SimpleNamespace
from unittest.mock import AsyncMock, patch

from tcppeer.pd import (
    ADVERTISE,
    OPT_IA_PD,
    OPT_IAPREFIX,
    OPT_SERVERID,
    REPLY,
    DelegatedPrefix,
    PrefixDelegationClient,
    _option,
)
from tcppeer.server import Server


def _build_pd_response(kind: int, transaction: bytes, prefix: str = "2001:db8:7f00::", prefix_len: int = 56) -> bytes:
    server_id = b"\x00\x01\x00\x01server"
    iaprefix = struct.pack("!IIB", 3600, 7200, prefix_len) + ipaddress.IPv6Address(prefix).packed
    ia_pd = struct.pack("!III", 1, 1800, 2880) + _option(OPT_IAPREFIX, iaprefix)
    body = _option(OPT_SERVERID, server_id) + _option(OPT_IA_PD, ia_pd)
    return bytes((kind,)) + transaction + body


def test_recv_matching_ignores_unrelated_packets() -> None:
    async def run() -> DelegatedPrefix | None:
        client = PrefixDelegationClient.__new__(PrefixDelegationClient)
        tx = b"\xaa\xbb\xcc"
        wrong_tx_pkt = _build_pd_response(ADVERTISE, b"\x01\x02\x03")
        wrong_kind_pkt = _build_pd_response(REPLY, tx)
        valid_pkt = _build_pd_response(ADVERTISE, tx)

        packets = iter([(wrong_tx_pkt, None), (wrong_kind_pkt, None), (valid_pkt, None)])
        loop = asyncio.get_running_loop()

        async def fake_recvfrom(_sock, _bufsize):
            return next(packets)

        with patch.object(loop, "sock_recvfrom", side_effect=fake_recvfrom):
            return await client._recv_matching(loop, object(), tx, ADVERTISE, 1.0)

    result = asyncio.run(run())
    assert result is not None
    assert result.prefix == ipaddress.IPv6Network("2001:db8:7f00::/56")


def test_server_keeps_pd_until_three_consecutive_failures() -> None:
    async def run() -> None:
        server = Server.__new__(Server)
        server.tun = SimpleNamespace(name="tcppeer0")
        server._pd_active = True
        server._pd_consecutive_failures = 0
        server._active_ipv6_prefix = ipaddress.IPv6Network("2001:db8:7f00::/64")
        fallback_calls = 0

        async def fake_fallback() -> None:
            nonlocal fallback_calls
            fallback_calls += 1
            server._pd_active = False
            server._pd_consecutive_failures = 0

        server._switch_to_ipv6_fallback = fake_fallback

        with (
            patch("tcppeer.server.discover_ipv6_upstream", return_value="wlp2s0"),
            patch("tcppeer.server.PrefixDelegationClient") as pd_cls,
        ):
            pd_cls.return_value.acquire = AsyncMock(return_value=None)

            await server._refresh_pd_once(max_consecutive_failures=3)
            assert fallback_calls == 0
            assert server._pd_active is True
            assert server._pd_consecutive_failures == 1

            await server._refresh_pd_once(max_consecutive_failures=3)
            assert fallback_calls == 0
            assert server._pd_active is True
            assert server._pd_consecutive_failures == 2

            # A successful renewal resets the failure counter
            pd_cls.return_value.acquire = AsyncMock(
                return_value=DelegatedPrefix(
                    prefix=ipaddress.IPv6Network("2001:db8:7f00::/56"),
                    preferred_lifetime=3600,
                    valid_lifetime=7200,
                    server_id=b"srv",
                )
            )
            await server._refresh_pd_once(max_consecutive_failures=3)
            assert server._pd_consecutive_failures == 0
            assert fallback_calls == 0

            # Three consecutive failures finally trigger fallback
            pd_cls.return_value.acquire = AsyncMock(return_value=None)
            await server._refresh_pd_once(max_consecutive_failures=3)
            await server._refresh_pd_once(max_consecutive_failures=3)
            assert fallback_calls == 0
            await server._refresh_pd_once(max_consecutive_failures=3)
            assert fallback_calls == 1
            assert server._pd_active is False

    asyncio.run(run())
