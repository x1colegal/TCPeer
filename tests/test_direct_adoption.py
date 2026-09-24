import asyncio
import socket
from types import SimpleNamespace

from tcppeer.server import Server


class FakeWriter:
    def __init__(self) -> None:
        self.closed = False

    def get_extra_info(self, _name):
        return None

    def close(self) -> None:
        self.closed = True

    async def wait_closed(self) -> None:
        return None


def test_duplicate_can_win_before_data_by_deterministic_key() -> None:
    server = Server.__new__(Server)
    server._direct_owner_committed = set()

    assert server._incoming_direct_wins("phone", ("z", "z"), ("a", "a"))
    assert not server._incoming_direct_wins("phone", ("a", "a"), ("z", "z"))


def test_late_duplicate_cannot_replace_connection_carrying_data() -> None:
    server = Server.__new__(Server)
    server._direct_owner_committed = {"phone"}

    assert not server._incoming_direct_wins("phone", ("z", "z"), ("a", "a"))


def test_new_peer_session_retires_black_holed_direct_owner() -> None:
    server = Server.__new__(Server)
    stale = FakeWriter()
    server.direct_writers = {"phone": stale}
    server._direct_owner_tokens = {"phone": "old-token"}
    server._direct_owner_keys = {"phone": ("a", "b")}
    server._direct_owner_peer_sessions = {"phone": "old-session"}
    server._direct_owner_committed = {"phone"}

    assert server._prepare_owner_for_peer_session("phone", "new-session")
    assert stale.closed
    assert "phone" not in server.direct_writers
    assert "phone" not in server._direct_owner_tokens
    assert "phone" not in server._direct_owner_peer_sessions


def test_same_peer_session_keeps_healthy_direct_owner() -> None:
    server = Server.__new__(Server)
    current = FakeWriter()
    server.direct_writers = {"phone": current}
    server._direct_owner_peer_sessions = {"phone": "same-session"}

    assert not server._prepare_owner_for_peer_session("phone", "same-session")
    assert not current.closed
    assert server.direct_writers["phone"] is current


def test_failed_stale_write_never_removes_new_owner() -> None:
    server = Server.__new__(Server)
    stale = FakeWriter()
    current = FakeWriter()
    server._direct_adoption_lock = asyncio.Lock()
    server.direct_writers = {"phone": current}
    server._direct_owner_tokens = {"phone": "new-token"}
    server._direct_owner_keys = {"phone": ("a", "b")}
    server._direct_owner_committed = {"phone"}
    server.store = SimpleNamespace(update_peer=lambda *_args, **_kwargs: None)

    async def after_direct_data(_peer_id: str) -> None:
        raise AssertionError("new owner must not be released")

    server._after_direct_data = after_direct_data

    released = asyncio.run(
        server._discard_direct_writer("phone", stale, "test-reset")
    )

    assert not released
    assert stale.closed
    assert server.direct_writers["phone"] is current
    assert server._direct_owner_tokens["phone"] == "new-token"


def test_auto_discovered_ipv4_gets_a_passive_listener(monkeypatch) -> None:
    server = Server.__new__(Server)
    server.config = SimpleNamespace(direct_port=7444)
    server._direct_bind_ipv6 = "2001:db8::10"
    server._direct_bind_ipv4 = "192.0.2.10"
    server._listeners = []
    calls = []

    async def fake_start_server(callback, address, port, *, family, reuse_port):
        calls.append((address, port, family, reuse_port))
        return object()

    monkeypatch.setattr(asyncio, "start_server", fake_start_server)
    asyncio.run(server._start_direct_listeners())

    assert calls == [
        ("2001:db8::10", 7444, socket.AF_INET6, True),
        ("192.0.2.10", 7444, socket.AF_INET, True),
    ]


def test_passive_adoption_selects_only_same_peer_active_attempt_for_cancel() -> None:
    async def scenario() -> None:
        server = Server.__new__(Server)
        phone_attempt = asyncio.create_task(asyncio.Event().wait())
        laptop_attempt = asyncio.create_task(asyncio.Event().wait())
        server._direct_connect_tasks = {
            "phone": phone_attempt,
            "laptop": laptop_attempt,
        }

        assert server._competing_direct_connect_task("phone", asyncio.current_task()) is phone_attempt
        assert server._competing_direct_connect_task("missing", asyncio.current_task()) is None

        phone_attempt.cancel()
        laptop_attempt.cancel()
        await asyncio.gather(phone_attempt, laptop_attempt, return_exceptions=True)

    asyncio.run(scenario())


def test_initiated_adoption_does_not_cancel_its_own_attempt() -> None:
    async def scenario() -> None:
        server = Server.__new__(Server)
        current = asyncio.current_task()
        server._direct_connect_tasks = {"phone": current}

        assert server._competing_direct_connect_task("phone", current) is None

    asyncio.run(scenario())


def test_direct_connect_done_callback_retrieves_failure() -> None:
    async def scenario() -> None:
        server = Server.__new__(Server)

        async def fail() -> None:
            raise RuntimeError("expected failure")

        task = asyncio.create_task(fail())
        server._direct_connect_tasks = {"phone": task}
        await asyncio.wait({task})
        server._clear_direct_connect_task("phone", task)

        assert "phone" not in server._direct_connect_tasks
        assert task.exception() is not None

    asyncio.run(scenario())
