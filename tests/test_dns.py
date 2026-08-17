from tcppeer import dns


def test_upstream_dns_uses_default_route_interfaces_and_filters_stub(monkeypatch):
    outputs = {
        ("ip", "-4", "route", "show", "default"): "default via 192.168.1.1 dev wlan0\n",
        ("ip", "-6", "route", "show", "default"): "default via fe80::1 dev wlan0\n",
        ("resolvectl", "dns", "wlan0"): "Link 3 (wlan0): 192.168.1.1 fd73:cafe:cafe::53 127.0.0.53 fe80::53\n",
    }
    monkeypatch.setattr(dns, "_command", lambda command: outputs.get(command, ""))
    assert dns.discover_upstream_dns({"tcppeer0"}) == ("192.168.1.1", "fd73:cafe:cafe::53")


def test_manual_dns_configuration_remains_an_override():
    from tcppeer.config import ServerConfig

    config = ServerConfig(
        coordinator_address="example", coordinator_port=7443,
        network="home", peer_id="server", secret="secret",
        dns=("9.9.9.9",),
    )
    assert config.dns == ("9.9.9.9",)
