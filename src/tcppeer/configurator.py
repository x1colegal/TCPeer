"""Interactive TCPeer configuration and systemd installer."""

from __future__ import annotations

import getpass
import ipaddress
from importlib import resources
import os
from pathlib import Path
import pwd
import grp
import shutil
import subprocess
import sys

if __package__ in {None, ""}:
    sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from tcppeer.config import CoordinatorConfig, ServerConfig

def ask(prompt: str, default: str | None = None, secret: bool = False) -> str:
    suffix = f" [{default}]" if default is not None else ""
    while True:
        value = (getpass.getpass if secret else input)(f"{prompt}{suffix}: ").strip()
        if value:
            return value
        if default is not None:
            return default
        print("A value is required.")


def ask_yes_no(prompt: str, default: bool = False) -> bool:
    marker = "Y/n" if default else "y/N"
    value = input(f"{prompt} [{marker}]: ").strip().casefold()
    return default if not value else value in {"y", "yes"}


def quote(value: str) -> str:
    return '"' + value.replace("\\", "\\\\").replace('"', '\\"') + '"'


def coordinator_text() -> str:
    ipv4 = ask("IPv4 listen address", "0.0.0.0")
    ipv6 = ask("IPv6 listen address", "::")
    port = int(ask("TCP listen port", "7443"))
    network = ask("Network name", "home")
    secret = ask("Network secret", secret=True)
    peernet_hosting = ask_yes_no("Enable Linux-only PeerNet Hosting administration", False)
    hosting_peer_ids = []
    hosting_state_db = Path("/var/lib/tcppeer/coordinator/state.db")
    if peernet_hosting:
        hosting_peer_ids = [item.strip() for item in ask("Linux host peer IDs, comma-separated").split(",") if item.strip()]
        hosting_state_db = Path(ask("PeerNet Hosting state path", str(hosting_state_db)))
    log_level = ask("Log level", "INFO").upper()
    config = CoordinatorConfig(
        ipv4, ipv6, port, {network: secret}, log_level,
        peernet_hosting=peernet_hosting,
        hosting_peer_ids=tuple(hosting_peer_ids),
        hosting_state_db=hosting_state_db,
    )
    if not 1 <= config.port <= 65535:
        raise ValueError("TCP port must be between 1 and 65535")
    hosts_text = ", ".join(quote(item) for item in hosting_peer_ids)
    return f"""# TCPeer coordinator configuration. Only the Secret Key proof is protected.
[listen]
ipv4 = {quote(ipv4)}
ipv6 = {quote(ipv6)}
port = {port}

[auth.networks]
{quote(network)} = {quote(secret)}

[peernet_hosting]
enabled = {str(peernet_hosting).lower()}
hosts = [{hosts_text}]
state_db = {quote(str(hosting_state_db))}

[runtime]
log_level = {quote(log_level)}
max_message_size = 16384
keepalive_seconds = 30
"""


def server_text() -> tuple[str, Path]:
    coordinator = ask("Coordinator DNS name or IP address")
    coordinator_port = int(ask("Coordinator TCP port", "7443"))
    network = ask("Network name", "home")
    peer_id = ask("Peer/server ID")
    secret = ask("Network secret", secret=True)
    direct_ipv4 = ask("Direct IPv4 address (empty means none)", "")
    direct_ipv6 = ask("Direct IPv6 address (empty means none)", "")
    direct_port = int(ask("Direct TCP port", "7444"))
    target_peer = ask("Preferred peer ID (empty waits for coordination)", "")
    tun_name = ask("TUN interface", "tcppeer0")
    mtu = int(ask("MTU", "1400"))
    subnet = ipaddress.ip_network(ask("IPv4 subnet", "10.50.0.0/24"))
    server4 = ipaddress.ip_address(ask("Server IPv4", "10.50.0.1"))
    pool_start = ipaddress.ip_address(ask("DHCP pool start", "10.50.0.10"))
    pool_end = ipaddress.ip_address(ask("DHCP pool end", "10.50.0.250"))
    lease = int(ask("DHCP lease time in seconds", "86400"))
    prefix6 = ipaddress.ip_network(ask("IPv6 SLAAC prefix", "fdfe:cafe:cafe::/64"))
    server6 = ipaddress.ip_address(ask("Server IPv6", "fdfe:cafe:cafe::1"))
    ra_interval = int(ask("RA interval in seconds", "30"))
    router_lifetime = int(ask("Router lifetime in seconds", "1800"))
    preferred = int(ask("Prefix preferred lifetime in seconds", "3600"))
    valid = int(ask("Prefix valid lifetime in seconds", "86400"))
    dns = [
        item.strip()
        for item in ask(
            "DNS override, comma-separated (empty auto-detects upstream)",
            "",
        ).split(",")
        if item.strip()
    ]
    exit_node_enabled = ask_yes_no("Enable exit-node forwarding", True)
    nat44 = ask_yes_no("Enable nftables NAT44 masquerade", True)
    nat66 = ask_yes_no("Enable nftables NAT66 masquerade", True)
    software_flow_offload = ask_yes_no("Enable nftables software flow offloading", True)
    peernet_hosting = ask_yes_no("Enable Linux-only PeerNet Hosting control", False)
    log_level = ask("Log level", "INFO").upper()
    state_db = Path(ask("SQLite state path", "/var/lib/tcppeer/server/state.db"))
    ServerConfig(
        coordinator_address=coordinator, coordinator_port=coordinator_port,
        network=network, peer_id=peer_id, secret=secret,
        direct_ipv4=direct_ipv4 or None, direct_ipv6=direct_ipv6 or None,
        direct_port=direct_port, tun_name=tun_name, mtu=mtu,
        ipv4_subnet=subnet, server_ipv4=server4, pool_start=pool_start,
        pool_end=pool_end, lease_seconds=lease, ipv6_prefix=prefix6,
        server_ipv6=server6, ra_interval_seconds=ra_interval,
        router_lifetime_seconds=router_lifetime,
        preferred_lifetime_seconds=preferred, valid_lifetime_seconds=valid,
        dns=tuple(dns), state_db=state_db, log_level=log_level,
        target_peer=target_peer or None,
        exit_node_enabled=exit_node_enabled, nat44=nat44, nat66=nat66,
        software_flow_offload=software_flow_offload,
        peernet_hosting=peernet_hosting,
    )
    dns_text = ", ".join(quote(item) for item in dns)
    content = f"""# TCPeer server configuration. Only the Secret Key proof is protected.
[coordinator]
address = {quote(coordinator)}
port = {coordinator_port}

[identity]
network = {quote(network)}
peer_id = {quote(peer_id)}
secret = {quote(secret)}

[direct]
ipv4 = {quote(direct_ipv4)}
ipv6 = {quote(direct_ipv6)}
port = {direct_port}
target_peer = {quote(target_peer)}

[interface]
name = {quote(tun_name)}
mtu = {mtu}

[exit_node]
enabled = {str(exit_node_enabled).lower()}
nat44 = {str(nat44).lower()}
nat66 = {str(nat66).lower()}
software_flow_offload = {str(software_flow_offload).lower()}

[peernet_hosting]
enabled = {str(peernet_hosting).lower()}
admin_socket = "/run/tcppeer/admin.sock"

[ipv4]
subnet = {quote(str(subnet))}
server = {quote(str(server4))}
pool_start = {quote(str(pool_start))}
pool_end = {quote(str(pool_end))}
lease_seconds = {lease}

[ipv6]
prefix = {quote(str(prefix6))}
server = {quote(str(server6))}
ra_interval_seconds = {ra_interval}
router_lifetime_seconds = {router_lifetime}
preferred_lifetime_seconds = {preferred}
valid_lifetime_seconds = {valid}
dns = [{dns_text}]

[paths]
state_db = {quote(str(state_db))}

[runtime]
log_level = {quote(log_level)}
"""
    return content, state_db


def install(component: str, content: str, state_db: Path | None, user: str, group: str) -> None:
    pwd.getpwnam(user)
    grp.getgrnam(group)
    command_name = f"tcppeer-{component}"
    executable = shutil.which(command_name)
    if executable is None:
        raise FileNotFoundError(
            f"{command_name} is not installed; install TCPeer first. On Debian 12, run "
            "'sudo python3 -m pip install --break-system-packages .' from the project root"
        )
    config_dir = Path("/etc/tcppeer")
    config_dir.mkdir(mode=0o750, parents=True, exist_ok=True)
    config_path = config_dir / f"{component}.toml"
    config_path.write_text(content, encoding="ascii")
    config_path.chmod(0o640)
    if state_db is not None:
        state_db.parent.mkdir(mode=0o750, parents=True, exist_ok=True)
        shutil.chown(state_db.parent, user=user, group=group)
    unit_name = f"tcppeer-{component}.service"
    unit_target = Path("/etc/systemd/system") / unit_name
    unit_text = resources.files("tcppeer").joinpath("systemd", unit_name).read_text(encoding="ascii")
    unit_text = unit_text.replace("User=tcppeer", f"User={user}").replace("Group=tcppeer", f"Group={group}")
    unit_text = unit_text.replace(f"/usr/local/bin/{command_name}", executable)
    unit_target.write_text(unit_text, encoding="ascii")
    shutil.chown(config_path, group=group)
    subprocess.run(("systemctl", "daemon-reload"), check=True)
    print(f"Installed {config_path} and {unit_target}.")
    if ask_yes_no(f"Enable and start {unit_name} now"):
        subprocess.run(("systemctl", "enable", "--now", unit_name), check=True)


def main() -> None:
    if os.geteuid() != 0:
        raise SystemExit(
            "TCPeer configurator must run as root. Use: sudo python3 configure.py"
        )
    print("Which component do you want to configure?")
    print("1. Coordinator")
    print("2. Server")
    selection = ask("Selection")
    if selection not in {"1", "2"}:
        raise SystemExit("Selection must be 1 or 2.")
    component = "coordinator" if selection == "1" else "server"
    user = ask("Linux user", "tcppeer")
    group = ask("Linux group", "tcppeer")
    try:
        if component == "coordinator":
            install(component, coordinator_text(), None, user, group)
        else:
            content, state_db = server_text()
            install(component, content, state_db, user, group)
    except (ValueError, OSError, subprocess.CalledProcessError, KeyError) as exc:
        raise SystemExit(f"Configuration failed: {exc}") from exc


if __name__ == "__main__":
    main()
