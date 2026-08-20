"""TOML configuration models and validation."""

from __future__ import annotations

from dataclasses import dataclass, field
import ipaddress
from pathlib import Path
import tomllib
import sys
from typing import Any


class ConfigurationError(ValueError):
    """Raised when a TCPeer configuration is invalid."""


def _table(data: dict[str, Any], name: str) -> dict[str, Any]:
    value = data.get(name, {})
    if not isinstance(value, dict):
        raise ConfigurationError(f"{name} must be a TOML table")
    return value


def _port(value: Any, name: str) -> int:
    try:
        result = int(value)
    except (TypeError, ValueError) as exc:
        raise ConfigurationError(f"{name} must be an integer") from exc
    if not 1 <= result <= 65535:
        raise ConfigurationError(f"{name} must be between 1 and 65535")
    return result


@dataclass(frozen=True)
class CoordinatorConfig:
    listen_ipv4: str | None = "0.0.0.0"
    listen_ipv6: str | None = "::"
    port: int = 7443
    networks: dict[str, str] = field(default_factory=dict)
    log_level: str = "INFO"
    max_message_size: int = 16384
    keepalive_seconds: int = 30
    @classmethod
    def from_file(cls, path: str | Path) -> "CoordinatorConfig":
        with Path(path).open("rb") as source:
            data = tomllib.load(source)
        listen = _table(data, "listen")
        auth = _table(data, "auth")
        runtime = _table(data, "runtime")
        networks = auth.get("networks", {})
        if not isinstance(networks, dict) or not networks:
            raise ConfigurationError("auth.networks must contain at least one network secret")
        return cls(
            listen_ipv4=listen.get("ipv4"),
            listen_ipv6=listen.get("ipv6"),
            port=_port(listen.get("port", 7443), "listen.port"),
            networks={str(k): str(v) for k, v in networks.items()},
            log_level=str(runtime.get("log_level", "INFO")).upper(),
            max_message_size=int(runtime.get("max_message_size", 16384)),
            keepalive_seconds=int(runtime.get("keepalive_seconds", 30)),
        )


@dataclass(frozen=True)
class ServerConfig:
    coordinator_address: str
    coordinator_port: int
    network: str
    peer_id: str
    secret: str
    direct_ipv4: str | None = None
    direct_ipv6: str | None = None
    direct_port: int = 7444
    tun_name: str = "tcppeer0"
    mtu: int = 1400
    ipv4_subnet: ipaddress.IPv4Network = ipaddress.ip_network("10.50.0.0/24")
    server_ipv4: ipaddress.IPv4Address = ipaddress.ip_address("10.50.0.1")
    pool_start: ipaddress.IPv4Address = ipaddress.ip_address("10.50.0.10")
    pool_end: ipaddress.IPv4Address = ipaddress.ip_address("10.50.0.250")
    lease_seconds: int = 86400
    ipv6_prefix: ipaddress.IPv6Network = ipaddress.ip_network("fdfe:cafe:cafe::/64")
    server_ipv6: ipaddress.IPv6Address = ipaddress.ip_address("fdfe:cafe:cafe::1")
    ra_interval_seconds: int = 30
    router_lifetime_seconds: int = 1800
    preferred_lifetime_seconds: int = 3600
    valid_lifetime_seconds: int = 86400
    dns: tuple[str, ...] = ()
    state_db: Path = Path("/var/lib/tcppeer/server/state.db")
    log_level: str = "INFO"
    target_peer: str | None = None
    exit_node_enabled: bool = True
    nat44: bool = True
    nat66: bool = True
    software_flow_offload: bool = True

    def __post_init__(self) -> None:
        if not self.coordinator_address.strip():
            raise ConfigurationError("coordinator DNS name or IP address is required")
        if "://" in self.coordinator_address:
            raise ConfigurationError("coordinator address must be a DNS name or IP address, not a URL")
        if not self.peer_id or any(ord(c) > 127 for c in self.peer_id):
            raise ConfigurationError("peer ID must be non-empty ASCII")
        if self.server_ipv4 not in self.ipv4_subnet:
            raise ConfigurationError("server IPv4 address is outside the IPv4 subnet")
        if self.pool_start not in self.ipv4_subnet or self.pool_end not in self.ipv4_subnet:
            raise ConfigurationError("DHCP pool is outside the IPv4 subnet")
        if int(self.pool_start) > int(self.pool_end):
            raise ConfigurationError("DHCP pool start is after its end")
        if self.server_ipv4 >= self.pool_start and self.server_ipv4 <= self.pool_end:
            raise ConfigurationError("DHCP pool contains the server IPv4 address")
        if self.server_ipv6 not in self.ipv6_prefix:
            raise ConfigurationError("server IPv6 address is outside the IPv6 prefix")
        if self.ipv6_prefix.prefixlen != 64:
            raise ConfigurationError("SLAAC requires an IPv6 /64 prefix")
        if not 576 <= self.mtu <= 65535:
            raise ConfigurationError("MTU must be between 576 and 65535")

    @classmethod
    def from_file(cls, path: str | Path) -> "ServerConfig":
        with Path(path).open("rb") as source:
            data = tomllib.load(source)
        coordinator = _table(data, "coordinator")
        identity = _table(data, "identity")
        interface = _table(data, "interface")
        direct = _table(data, "direct")
        ipv4 = _table(data, "ipv4")
        ipv6 = _table(data, "ipv6")
        paths = _table(data, "paths")
        runtime = _table(data, "runtime")
        exit_node = _table(data, "exit_node")
        try:
            return cls(
                coordinator_address=str(coordinator["address"]),
                coordinator_port=_port(coordinator.get("port", 7443), "coordinator.port"),
                network=str(identity["network"]),
                peer_id=str(identity["peer_id"]),
                secret=str(identity["secret"]),
                direct_ipv4=direct.get("ipv4") or None,
                direct_ipv6=direct.get("ipv6") or None,
                direct_port=_port(direct.get("port", 7444), "direct.port"),
                tun_name=str(interface.get("name", "tcppeer0")),
                mtu=int(interface.get("mtu", 1400)),
                ipv4_subnet=ipaddress.ip_network(ipv4.get("subnet", "10.50.0.0/24")),
                server_ipv4=ipaddress.ip_address(ipv4.get("server", "10.50.0.1")),
                pool_start=ipaddress.ip_address(ipv4.get("pool_start", "10.50.0.10")),
                pool_end=ipaddress.ip_address(ipv4.get("pool_end", "10.50.0.250")),
                lease_seconds=int(ipv4.get("lease_seconds", 86400)),
                ipv6_prefix=ipaddress.ip_network(ipv6.get("prefix", "fdfe:cafe:cafe::/64")),
                server_ipv6=ipaddress.ip_address(ipv6.get("server", "fdfe:cafe:cafe::1")),
                ra_interval_seconds=int(ipv6.get("ra_interval_seconds", 30)),
                router_lifetime_seconds=int(ipv6.get("router_lifetime_seconds", 1800)),
                preferred_lifetime_seconds=int(ipv6.get("preferred_lifetime_seconds", 3600)),
                valid_lifetime_seconds=int(ipv6.get("valid_lifetime_seconds", 86400)),
                dns=tuple(str(item) for item in ipv6.get("dns", [])),
                state_db=Path(paths.get("state_db", "/var/lib/tcppeer/server/state.db")),
                log_level=str(runtime.get("log_level", "INFO")).upper(),
                target_peer=direct.get("target_peer") or None,
                exit_node_enabled=bool(exit_node.get("enabled", True)),
                nat44=bool(exit_node.get("nat44", True)),
                nat66=bool(exit_node.get("nat66", True)),
                software_flow_offload=bool(exit_node.get("software_flow_offload", True)),
            )
        except KeyError as exc:
            raise ConfigurationError(f"missing required setting: {exc.args[0]}") from exc
        except ValueError as exc:
            if isinstance(exc, ConfigurationError):
                raise
            raise ConfigurationError(str(exc)) from exc
