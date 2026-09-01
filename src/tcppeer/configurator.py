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

from tcppeer.config import ClientConfig, CoordinatorConfig, ServerConfig

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


def coordinator_text() -> tuple[str, Path]:
    ipv4 = ask("IPv4 listen address", "0.0.0.0")
    ipv6 = ask("IPv6 listen address", "::")
    port = int(ask("TCP listen port", "7443"))
    network = ask("Network name", "home")
    secret = ask("Network secret", secret=True)
    log_level = ask("Log level", "INFO").upper()
    state_db = Path(ask("Persistent device database", "/var/lib/tcppeer/coordinator/state.db"))
    config = CoordinatorConfig(
        listen_ipv4=ipv4, listen_ipv6=ipv6, port=port,
        networks={network: secret}, log_level=log_level, state_db=state_db,
    )
    if not 1 <= config.port <= 65535:
        raise ValueError("TCP port must be between 1 and 65535")
    return f"""# TCPeer coordinator configuration. Only the Secret Key proof is protected.
[listen]
ipv4 = {quote(ipv4)}
ipv6 = {quote(ipv6)}
port = {port}

[auth.networks]
{quote(network)} = {quote(secret)}

[paths]
state_db = {quote(str(state_db))}

[runtime]
log_level = {quote(log_level)}
max_message_size = 16384
keepalive_seconds = 30
""", state_db


def client_text() -> tuple[str, Path]:
    coordinator = ask("Coordinator DNS name or IP address")
    coordinator_port = int(ask("Coordinator TCP port", "7443"))
    network = ask("Network name", "home")
    peer_id = ask("Client peer ID")
    secret = ask("Network secret", secret=True)
    target_peer = ask("Peer/Exit Node ID used for address assignment")
    use_exit_node = ask_yes_no("Route Internet and DNS through this Exit Node", False)
    direct_ipv4 = ask("Direct IPv4 address (empty auto-detects)", "")
    direct_ipv6 = ask("Direct IPv6 address (empty auto-detects)", "")
    direct_port = int(ask("Direct TCP port", "7444"))
    tun_name = ask("TUN interface", "tcppeer0")
    mtu = int(ask("MTU", "1400"))
    state_db = Path(ask("SQLite state path", "/var/lib/tcppeer/client/state.db"))
    log_level = ask("Log level", "INFO").upper()
    ClientConfig(coordinator, coordinator_port, network, peer_id, secret, target_peer,
                 use_exit_node, direct_ipv4 or None, direct_ipv6 or None, direct_port,
                 tun_name, mtu, state_db, log_level)
    return f"""# TCPeer Linux client configuration. Only the Secret Key proof is protected.
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

[routing]
use_exit_node = {str(use_exit_node).lower()}

[paths]
state_db = {quote(str(state_db))}

[runtime]
log_level = {quote(log_level)}
""", state_db


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
    devices_missing = component == "coordinator" and shutil.which("tcppeer-devices") is None
    if executable is None or devices_missing:
        project_root = Path(__file__).resolve().parents[2]
        if not (project_root / "pyproject.toml").is_file():
            raise FileNotFoundError(f"{command_name} is not installed and the TCPeer source tree was not found")
        print("Installing TCPeer command-line tools from the current source tree...")
        subprocess.run(
            (sys.executable, "-m", "pip", "install", "--break-system-packages", "--upgrade", str(project_root)),
            check=True,
        )
        executable = shutil.which(command_name)
    if executable is None or (component == "coordinator" and shutil.which("tcppeer-devices") is None):
        raise FileNotFoundError("TCPeer command-line installation completed but required commands are unavailable")
    config_dir = Path("/etc/tcppeer")
    config_dir.mkdir(mode=0o750, parents=True, exist_ok=True)
    config_dir.chmod(0o750)
    shutil.chown(config_dir, group=group)
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
    print("2. Exit Node / Server")
    print("3. Client")
    selection = ask("Selection")
    if selection not in {"1", "2", "3"}:
        raise SystemExit("Selection must be 1, 2, or 3.")
    component = {"1": "coordinator", "2": "server", "3": "client"}[selection]
    user = ask("Linux user", "tcppeer")
    group = ask("Linux group", "tcppeer")
    try:
        if component == "coordinator":
            content, state_db = coordinator_text()
            install(component, content, state_db, user, group)
        elif component == "server":
            content, state_db = server_text()
            install(component, content, state_db, user, group)
        else:
            content, state_db = client_text()
            install(component, content, state_db, user, group)
    except (ValueError, OSError, subprocess.CalledProcessError, KeyError) as exc:
        raise SystemExit(f"Configuration failed: {exc}") from exc


if __name__ == "__main__":
    main()
