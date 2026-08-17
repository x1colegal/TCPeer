"""Discover usable DNS servers from the Linux upstream network."""

from __future__ import annotations

import ipaddress
import logging
from pathlib import Path
import re
import subprocess

LOG = logging.getLogger("tcppeer.dns")
_DEFAULT_DEVICE = re.compile(r"\bdev\s+(\S+)")


def discover_upstream_dns(excluded_interfaces: set[str] | None = None) -> tuple[str, ...]:
    """Return DNS addresses attached to the active default-route interfaces."""
    excluded = excluded_interfaces or set()
    interfaces = _default_interfaces() - excluded
    addresses: list[str] = []
    for interface in interfaces:
        addresses.extend(_resolvectl_dns(interface))
    if not addresses:
        for interface in interfaces:
            addresses.extend(_network_manager_dns(interface))
    if not addresses:
        addresses.extend(_resolv_conf_dns())
    result = _usable_unique(addresses)
    if result:
        LOG.info("Discovered upstream DNS servers: %s", ", ".join(result))
    else:
        LOG.warning("No usable upstream DNS server was discovered")
    return result


def _default_interfaces() -> set[str]:
    interfaces: set[str] = set()
    for command in (("ip", "-4", "route", "show", "default"), ("ip", "-6", "route", "show", "default")):
        output = _command(command)
        for line in output.splitlines():
            match = _DEFAULT_DEVICE.search(line)
            if match:
                interfaces.add(match.group(1))
    return interfaces


def _resolvectl_dns(interface: str) -> list[str]:
    output = _command(("resolvectl", "dns", interface))
    return _addresses_after_colon(output)


def _network_manager_dns(interface: str) -> list[str]:
    output = _command(("nmcli", "-g", "IP4.DNS,IP6.DNS", "device", "show", interface))
    return [token.strip() for token in output.replace(";", "\n").splitlines() if token.strip()]


def _resolv_conf_dns() -> list[str]:
    try:
        lines = Path("/etc/resolv.conf").read_text(encoding="ascii", errors="ignore").splitlines()
    except OSError:
        return []
    return [line.split()[1] for line in lines if line.split()[:1] == ["nameserver"] and len(line.split()) >= 2]


def _addresses_after_colon(output: str) -> list[str]:
    values: list[str] = []
    for line in output.splitlines():
        if ":" not in line:
            continue
        values.extend(line.split(":", 1)[1].split())
    return values


def _usable_unique(values: list[str]) -> tuple[str, ...]:
    result: list[str] = []
    for value in values:
        try:
            address = ipaddress.ip_address(value.split("%", 1)[0])
        except ValueError:
            continue
        if address.is_loopback or address.is_link_local or address.is_unspecified or address.is_multicast:
            continue
        text = str(address)
        if text not in result:
            result.append(text)
    return tuple(result)


def _command(command: tuple[str, ...]) -> str:
    try:
        result = subprocess.run(command, check=False, capture_output=True, text=True, timeout=3)
    except (OSError, subprocess.TimeoutExpired):
        return ""
    return result.stdout if result.returncode == 0 else ""
