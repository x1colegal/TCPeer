import pytest

from tcppeer import configurator


def test_configurator_requires_root(monkeypatch):
    monkeypatch.setattr(configurator.os, "geteuid", lambda: 1000)
    with pytest.raises(SystemExit, match="sudo python3 configure.py"):
        configurator.main()


def test_server_configuration_writes_exit_node_masquerade(monkeypatch):
    answers = iter([
        "coordinator.example", "7443", "home", "server", "cleartext",
        "", "", "7444", "", "tcppeer0", "1400",
        "10.50.0.0/24", "10.50.0.1", "10.50.0.10", "10.50.0.250", "86400",
        "fdfe:cafe:cafe::/64", "fdfe:cafe:cafe::1", "30", "1800", "3600", "86400",
        "", "INFO", "/tmp/tcppeer-state.db",
    ])
    monkeypatch.setattr(configurator, "ask", lambda *_args, **_kwargs: next(answers))
    yes_no = iter([True, True, True, True, True])
    monkeypatch.setattr(configurator, "ask_yes_no", lambda *_args, **_kwargs: next(yes_no))
    content, _state = configurator.server_text()
    assert "[exit_node]" in content
    assert "enabled = true" in content
    assert "nat44 = true" in content
    assert "nat66 = true" in content
    assert "software_flow_offload = true" in content
    assert "[peernet_hosting]" in content
    assert "enabled = true" in content
    assert "routed_subnets" not in content
    assert "dns = []" in content
