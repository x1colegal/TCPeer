from tcppeer.server import Server


def test_duplicate_can_win_before_data_by_deterministic_key() -> None:
    server = Server.__new__(Server)
    server._direct_owner_committed = set()

    assert server._incoming_direct_wins("phone", ("z", "z"), ("a", "a"))
    assert not server._incoming_direct_wins("phone", ("a", "a"), ("z", "z"))


def test_late_duplicate_cannot_replace_connection_carrying_data() -> None:
    server = Server.__new__(Server)
    server._direct_owner_committed = {"phone"}

    assert not server._incoming_direct_wins("phone", ("z", "z"), ("a", "a"))
