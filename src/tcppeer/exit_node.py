"""Linux forwarding and nftables NAT for a TCPeer exit node."""

from __future__ import annotations

import logging
import json
import re
import subprocess

from .config import ServerConfig

LOG = logging.getLogger("tcppeer.exit_node")
_INTERFACE = re.compile(r"^[A-Za-z0-9_.:-]{1,15}$")
_NFT_IDENTIFIER = re.compile(r"^[A-Za-z0-9_.:-]+$")
_INPUT_RULE_COMMENT = "tcppeer-open-input"


class ExitNodeError(RuntimeError):
    """Raised when exit-node forwarding cannot be configured."""


class ExitNodeFirewall:
    """Own isolated nftables tables and routes for one TCPeer TUN."""

    TABLES = (
        ("inet", "tcppeer_input"),
        ("inet", "tcppeer_forward"),
        ("ip", "tcppeer_nat44"),
        ("ip6", "tcppeer_nat66"),
    )

    def __init__(self, config: ServerConfig, interface: str):
        if not _INTERFACE.fullmatch(interface):
            raise ExitNodeError("invalid TUN interface name for nftables")
        self.config = config
        self.interface = interface
        self.nat66_enabled = config.nat66

    def apply(self) -> None:
        if self.config.exit_node_enabled:
            self._run(("sysctl", "-q", "-w", "net.ipv4.ip_forward=1"))
            self._run(("sysctl", "-q", "-w", "net.ipv6.conf.all.forwarding=1"))
        self._delete_tables()
        upstream = self._upstream_interfaces() if (
            self.config.exit_node_enabled and self.config.software_flow_offload
        ) else ()
        rules = self._ruleset(upstream)
        try:
            subprocess.run(
                ("nft", "-f", "-"), input=rules, text=True,
                check=True, capture_output=True,
            )
        except FileNotFoundError as exc:
            raise ExitNodeError("nft is not installed") from exc
        except subprocess.CalledProcessError as exc:
            if not upstream:
                raise ExitNodeError(f"nftables rejected the TCPeer rules: {exc.stderr.strip()}") from exc
            LOG.warning("Software flow offload is unsupported here; using normal forwarding: %s", exc.stderr.strip())
            self._delete_tables()
            try:
                subprocess.run(
                    ("nft", "-f", "-"), input=self._ruleset(()), text=True,
                    check=True, capture_output=True,
                )
            except subprocess.CalledProcessError as fallback_exc:
                raise ExitNodeError(f"nftables rejected the TCPeer rules: {fallback_exc.stderr.strip()}") from fallback_exc
            upstream = ()
        self._ensure_host_input_accepts()
        LOG.info(
            "Enabled forwarding with NAT44=%s NAT66=%s software-flow-offload=%s on %s",
            self.config.nat44, self.nat66_enabled, bool(upstream), self.interface,
        )

    def close(self) -> None:
        self._delete_tables()

    def _ruleset(self, upstream: tuple[str, ...] = ()) -> str:
        tun = self.interface
        flowtable = ""
        flow_rules = ""
        if upstream:
            devices = ", ".join(f'"{name}"' for name in (tun, *upstream))
            flowtable = f'''  flowtable fastpath {{
    hook ingress priority 0;
    devices = {{ {devices} }};
  }}
'''
            flow_rules = f'''    iifname "{tun}" oifname != "{tun}" ct state established,related flow add @fastpath
    iifname != "{tun}" oifname "{tun}" ct state established,related flow add @fastpath
'''
        sections = ['''table inet tcppeer_input {
  chain input {
    type filter hook input priority filter; policy accept;
    meta l4proto { tcp, udp } accept
    meta l4proto { icmp, ipv6-icmp } accept
  }
}''']
        if not self.config.exit_node_enabled:
            return "\n\n".join(sections) + "\n"
        sections.append(f'''table inet tcppeer_forward {{
{flowtable}  chain forward {{
    type filter hook forward priority filter; policy accept;
{flow_rules}
    iifname "{tun}" accept
    oifname "{tun}" ct state established,related accept
  }}
}}''')
        if self.config.nat44:
            sections.append(f'''table ip tcppeer_nat44 {{
  chain postrouting {{
    type nat hook postrouting priority srcnat; policy accept;
    iifname "{tun}" oifname != "{tun}" masquerade
  }}
}}''')
        if self.nat66_enabled:
            sections.append(f'''table ip6 tcppeer_nat66 {{
  chain postrouting {{
    type nat hook postrouting priority srcnat; policy accept;
    iifname "{tun}" oifname != "{tun}" masquerade
  }}
}}''')
        return "\n\n".join(sections) + "\n"

    def _upstream_interfaces(self) -> tuple[str, ...]:
        devices: set[str] = set()
        for family in (("-4",), ("-6",)):
            try:
                result = subprocess.run(
                    ("ip", "-o", *family, "route", "show", "default"),
                    check=False, capture_output=True, text=True,
                )
            except FileNotFoundError:
                return ()
            for line in result.stdout.splitlines():
                fields = line.split()
                if "dev" in fields:
                    index = fields.index("dev") + 1
                    if index < len(fields) and _INTERFACE.fullmatch(fields[index]) and fields[index] != self.interface:
                        devices.add(fields[index])
        return tuple(sorted(devices))

    def _ensure_host_input_accepts(self) -> None:
        """Insert accepts into existing priority-filter INPUT base chains.

        An accept verdict in our separate base chain is not final: a later
        INPUT base chain can still drop the packet. Insert at the front of the
        chains that make the host's normal filter decision as well.
        """
        try:
            result = subprocess.run(
                ("nft", "-j", "-a", "list", "ruleset"),
                check=True, capture_output=True, text=True,
            )
            objects = json.loads(result.stdout).get("nftables", [])
        except (FileNotFoundError, subprocess.CalledProcessError, json.JSONDecodeError) as exc:
            raise ExitNodeError(f"cannot inspect nftables INPUT chains: {exc}") from exc

        existing: set[tuple[str, str, str]] = set()
        chains: set[tuple[str, str, str]] = set()
        for item in objects:
            rule = item.get("rule")
            if rule and rule.get("comment") == _INPUT_RULE_COMMENT:
                existing.add((str(rule.get("family")), str(rule.get("table")), str(rule.get("chain"))))
            chain = item.get("chain")
            if not chain or chain.get("hook") != "input" or chain.get("type") != "filter":
                continue
            family = str(chain.get("family"))
            table = str(chain.get("table"))
            name = str(chain.get("name"))
            priority = chain.get("prio", chain.get("priority", 0))
            if priority not in {0, "filter"} or table == "tcppeer_input":
                continue
            if family not in {"ip", "ip6", "inet"}:
                continue
            if not _NFT_IDENTIFIER.fullmatch(table) or not _NFT_IDENTIFIER.fullmatch(name):
                LOG.warning("Skipping nftables chain with unsupported identifier: %s %s %s", family, table, name)
                continue
            chains.add((family, table, name))

        commands: list[str] = []
        for family, table, chain in sorted(chains - existing):
            protocols = "tcp, udp, icmp" if family == "ip" else "tcp, udp, ipv6-icmp"
            if family == "inet":
                protocols = "tcp, udp, icmp, ipv6-icmp"
            commands.append(
                f'insert rule {family} {table} {chain} meta l4proto {{ {protocols} }} '
                f'counter accept comment "{_INPUT_RULE_COMMENT}"'
            )
        if commands:
            try:
                subprocess.run(
                    ("nft", "-f", "-"), input="\n".join(commands) + "\n",
                    check=True, capture_output=True, text=True,
                )
            except subprocess.CalledProcessError as exc:
                raise ExitNodeError(f"cannot open host TCP/UDP/ICMP input: {exc.stderr.strip()}") from exc

    def _delete_tables(self) -> None:
        for family, table in self.TABLES:
            subprocess.run(("nft", "delete", "table", family, table), capture_output=True, check=False)

    @staticmethod
    def _run(command: tuple[str, ...]) -> None:
        try:
            subprocess.run(command, check=True, capture_output=True, text=True)
        except FileNotFoundError as exc:
            raise ExitNodeError(f"required command is not installed: {command[0]}") from exc
        except subprocess.CalledProcessError as exc:
            raise ExitNodeError(f"command failed: {' '.join(command)}: {exc.stderr.strip()}") from exc
