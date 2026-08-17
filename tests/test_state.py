import ipaddress

from tcppeer.state import StateStore


START = ipaddress.ip_address("10.50.0.10")
END = ipaddress.ip_address("10.50.0.12")


def test_leases_are_unique_and_persistent(tmp_path):
    path = tmp_path / "state.db"
    first_store = StateStore(path)
    first = first_store.allocate_lease("a", START, END, 300, now=100)
    second = first_store.allocate_lease("b", START, END, 300, now=100)
    assert first.address != second.address
    first_store.close()

    reopened = StateStore(path)
    assert reopened.get_lease("a").address == first.address
    assert reopened.get_lease("b").address == second.address
    reopened.close()


def test_expiration_makes_address_available(tmp_path):
    store = StateStore(tmp_path / "state.db")
    old = store.allocate_lease("old", START, START, 10, now=100)
    new = store.allocate_lease("new", START, START, 10, now=111)
    assert new.address == old.address
    assert store.get_lease("old") is None
    store.close()


def test_release_removes_lease(tmp_path):
    store = StateStore(tmp_path / "state.db")
    store.allocate_lease("client", START, END, 10, now=100)
    assert store.release_lease("client")
    assert store.get_lease("client") is None
    store.close()
