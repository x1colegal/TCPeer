"""Read-only operational CLI for TCPeer Linux server and client state."""

from __future__ import annotations

import argparse
import os
import select
import struct
import datetime as dt
from pathlib import Path
import sqlite3
import sys
import time
import socket

if __package__ in {None, ""}:
    sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from tcppeer.config import ClientConfig, ConfigurationError, ServerConfig
from tcppeer.tpp import ECHO_REPLY, build_tpp, parse_tpp


def _rows(connection: sqlite3.Connection, query: str) -> list[sqlite3.Row]:
    connection.row_factory = sqlite3.Row
    return list(connection.execute(query))


def _print_table(rows: list[sqlite3.Row], columns: list[str]) -> None:
    if not rows:
        print("No records.")
        return
    values = [[str(row[column] if row[column] is not None else "-") for column in columns] for row in rows]
    widths = [max(len(column), *(len(row[index]) for row in values)) for index, column in enumerate(columns)]
    print("  ".join(column.upper().ljust(widths[index]) for index, column in enumerate(columns)))
    for row in values:
        print("  ".join(value.ljust(widths[index]) for index, value in enumerate(row)))


LinuxConfig = ServerConfig | ClientConfig


def _default_config_path() -> Path:
    server = Path("/etc/tcppeer/server.toml")
    client = Path("/etc/tcppeer/client.toml")
    if server.is_file():
        return server
    if client.is_file():
        return client
    return server


def _load_config(path: str | Path) -> LinuxConfig:
    config_path = Path(path)
    try:
        import tomllib
        with config_path.open("rb") as source:
            tables = tomllib.load(source)
    except OSError:
        raise
    if "routing" in tables or config_path.name == "client.toml":
        return ClientConfig.from_file(config_path)
    return ServerConfig.from_file(config_path)


def run_command(config: LinuxConfig, command: str, use_peer_id: bool = False) -> None:
    connection = sqlite3.connect(f"file:{config.state_db}?mode=ro", uri=True)
    try:
        if command in {"status", "peers", "addresses", "transport", "stats"}:
            peers = _rows(connection, "SELECT COALESCE(NULLIF(display_name, ''), peer_id) AS name, * FROM peers ORDER BY name")
            identity = "peer_id" if use_peer_id else "name"
            if command == "status":
                connected = sum(row["transport"] not in {"Disconnected", "No Direct Connection"} for row in peers)
                role = "Client" if isinstance(config, ClientConfig) else "Server"
                name_row = connection.execute(
                    "SELECT value FROM metadata WHERE key='device_name'",
                ).fetchone()
                print(f"{role} device name: {name_row[0] if name_row else config.peer_id}")
                print(f"{role} peer ID: {config.peer_id}")
                print(f"TUN interface: {config.tun_name}")
                print(f"Connected peers: {connected}")
            elif command == "peers":
                _print_table(peers, [identity, "overlay_ipv4", "overlay_ipv6", "transport", "endpoint"])
            elif command == "addresses":
                _print_table(peers, [identity, "overlay_ipv4", "overlay_ipv6"])
            elif command == "transport":
                _print_table(peers, [identity, "transport", "endpoint"])
            else:
                _print_table(peers, [identity, "rx_bytes", "tx_bytes", "connected_at"])
        elif command == "leases":
            _print_table(_rows(connection, "SELECT * FROM leases ORDER BY address"), ["client_id", "address", "state", "starts_at", "expires_at"])
        elif command == "sessions":
            _print_table(_rows(connection, "SELECT * FROM sessions ORDER BY started_at DESC"), ["session_id", "peer_id", "family", "endpoint", "state", "started_at", "ended_at"])
    finally:
        connection.close()


def _run_ping(config: LinuxConfig, peer_id: str) -> None:
    import re
    import statistics

    client = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)

    transmitted = 0
    received = 0
    rtts: list[float] = []

    try:
        client.connect("/run/tcppeer/server-admin.sock")
        client.sendall(f"PING {peer_id}\n".encode("ascii"))

        print(f"TPP ping {peer_id} - IPv6 Next Header 99")

        try:
            with client.makefile("r", encoding="ascii") as stream:
                for line in stream:
                    line = line.rstrip("\n")

                    if line.startswith("ERROR "):
                        raise SystemExit(line[6:])

                    match = re.search(
                        r"seq=(\d+)\s+time=([0-9.]+)\s+ms",
                        line,
                    )

                    if match:
                        sequence = int(match.group(1))
                        rtt = float(match.group(2))

                        transmitted = max(transmitted, sequence)
                        received += 1
                        rtts.append(rtt)

                    elif line.startswith("timeout "):
                        match = re.search(r"seq=(\d+)", line)
                        if match:
                            transmitted = max(
                                transmitted,
                                int(match.group(1)),
                            )

                    print(line)

        except KeyboardInterrupt:
            print()

    finally:
        client.close()

        if transmitted > 0:
            lost = transmitted - received
            loss = (lost / transmitted) * 100.0

            print(f"--- {peer_id} TPP ping statistics ---")
            print(
                f"{transmitted} packets transmitted, "
                f"{received} received, "
                f"{loss:.1f}% packet loss"
            )

            if rtts:
                minimum = min(rtts)
                average = statistics.fmean(rtts)
                maximum = max(rtts)

                if len(rtts) >= 2:
                    jitter = statistics.fmean(
                        abs(current - previous)
                        for previous, current in zip(rtts, rtts[1:])
                    )
                else:
                    jitter = 0.0

                print(
                    "rtt min/avg/max/jitter = "
                    f"{minimum:.1f}/{average:.1f}/"
                    f"{maximum:.1f}/{jitter:.1f} ms"
                )


def _rename_device(name: str) -> None:
    if not (1 <= len(name) <= 64) or any(not 32 <= ord(char) <= 126 for char in name):
        raise SystemExit("Device name must contain 1-64 printable ASCII characters")
    client = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    try:
        client.connect("/run/tcppeer/server-admin.sock")
        client.sendall(f"RENAME {name}\n".encode("ascii"))
        response = client.makefile("r", encoding="ascii").readline().strip()
    finally:
        client.close()
    if response.startswith("ERROR "):
        raise SystemExit(response[6:])
    if not response.startswith("OK "):
        raise SystemExit("TCPeer service returned an invalid rename response")
    print(response[3:])


def _automatic_device_name() -> str:
    name = socket.gethostname().strip()
    if not name or len(name) > 64 or any(not 32 <= ord(char) <= 126 for char in name):
        raise SystemExit("The Linux hostname cannot be used as a TCPeer device name")
    return name


def _resolve_peer(config: LinuxConfig, value: str, use_peer_id: bool) -> str:
    if use_peer_id:
        return value
    connection = sqlite3.connect(f"file:{config.state_db}?mode=ro", uri=True)
    try:
        rows = connection.execute(
            "SELECT peer_id FROM peers WHERE display_name = ? OR "
            "(COALESCE(display_name, '') = '' AND peer_id = ?)",
            (value, value),
        ).fetchall()
    finally:
        connection.close()
    if not rows:
        raise SystemExit(f"Unknown device name: {value}. Use --peer-id to select by Peer ID.")
    if len(rows) > 1:
        raise SystemExit(f"Device name is ambiguous: {value}. Use --peer-id with the exact Peer ID.")
    return str(rows[0][0])


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Inspect TCPeer Linux server or client state")
    parser.add_argument("--config", help="configuration file (auto-detects server.toml or client.toml by default)")
    parser.add_argument(
        "--peer-id", action="store_true", dest="use_peer_id",
        help="Optional. Use Peer ID instead of Device Name.",
    )
    parser.add_argument(
        "--automatic", action="store_true",
        help="with rename, restore the automatic Linux hostname",
    )
    parser.add_argument("command", choices=("status", "peers", "leases", "sessions", "addresses", "transport", "stats", "ping", "rename"))
    parser.add_argument("target", nargs="?", help="Device name, or a Peer-ID when --peer-id is used")
    return parser


def main() -> None:
    args = build_parser().parse_args()
    try:
        config_path = Path(args.config) if args.config else _default_config_path()
        config = _load_config(config_path)
        if args.command == "ping":
            if not args.target:
                raise SystemExit('Usage: tcppeer ping "Device name" [--peer-id]')
            _run_ping(config, _resolve_peer(config, args.target, args.use_peer_id))
        elif args.command == "rename":
            if args.automatic and args.target:
                raise SystemExit("Choose either a device name or --automatic, not both")
            if not args.automatic and not args.target:
                raise SystemExit(
                    'Usage: tcppeer rename "Device name" or tcppeer rename --automatic'
                )
            _rename_device(_automatic_device_name() if args.automatic else args.target)
        else:
            run_command(config, args.command, args.use_peer_id)
    except (OSError, ConfigurationError, sqlite3.Error) as exc:
        raise SystemExit(f"Cannot read TCPeer state: {exc}") from exc


if __name__ == "__main__":
    main()
