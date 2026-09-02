from tcppeer.coordinator import Coordinator


def test_shared_usable_ipv6_prefix_does_not_require_same_observed_origin() -> None:
    assert Coordinator._can_use_local_candidates(
        "2000:dead:beef:0::10",
        "2000:dead:beef:0::20",
        6,
        same_public_origin=False,
    )


def test_link_local_ipv6_is_not_selected_as_unscoped_local_candidate() -> None:
    assert not Coordinator._can_use_local_candidates(
        "fe80::10",
        "fe80::20",
        6,
        same_public_origin=True,
    )


def test_private_ipv4_still_requires_same_observed_origin() -> None:
    assert not Coordinator._can_use_local_candidates(
        "192.168.83.10",
        "192.168.83.20",
        4,
        same_public_origin=False,
    )
    assert Coordinator._can_use_local_candidates(
        "192.168.83.10",
        "192.168.83.20",
        4,
        same_public_origin=True,
    )
