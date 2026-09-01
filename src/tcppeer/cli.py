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


def run_command(config: LinuxConfig, command: str, peer_id: str | None = None) -> None:
    connection = sqlite3.connect(f"file:{config.state_db}?mode=ro", uri=True)
    try:
        if command in {"status", "peers", "addresses", "transport", "stats"}:
            peers = _rows(connection, "SELECT * FROM peers ORDER BY peer_id")
            if command == "status":
                connected = sum(row["transport"] not in {"Disconnected", "No Direct Connection"} for row in peers)
                role = "Client" if isinstance(config, ClientConfig) else "Server"
                print(f"{role} peer ID: {config.peer_id}")
                print(f"TUN interface: {config.tun_name}")
                print(f"Connected peers: {connected}")
            elif command == "peers":
                _print_table(peers, ["peer_id", "overlay_ipv4", "overlay_ipv6", "transport", "endpoint"])
            elif command == "addresses":
                _print_table(peers, ["peer_id", "overlay_ipv4", "overlay_ipv6"])
            elif command == "transport":
                _print_table(peers, ["peer_id", "transport", "endpoint"])
            else:
                _print_table(peers, ["peer_id", "rx_bytes", "tx_bytes", "connected_at"])
        elif command == "leases":
            _print_table(_rows(connection, "SELECT * FROM leases ORDER BY address"), ["client_id", "address", "state", "starts_at", "expires_at"])
        elif command == "sessions":
            _print_table(_rows(connection, "SELECT * FROM sessions ORDER BY started_at DESC"), ["session_id", "peer_id", "family", "endpoint", "state", "started_at", "ended_at"])
    finally:
        connection.close()


def _run_ping(config: LinuxConfig, peer_id: str) -> None:
    import re
    import socket
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


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Inspect TCPeer Linux server or client state")
    parser.add_argument("--config", help="configuration file (auto-detects server.toml or client.toml by default)")
    parser.add_argument("command", choices=("status", "peers", "leases", "sessions", "addresses", "transport", "stats", "ping"))
    parser.add_argument("peer_id", nargs="?")
    return parser


def main() -> None:
    args = build_parser().parse_args()
    try:
        config_path = Path(args.config) if args.config else _default_config_path()
        config = _load_config(config_path)
        if args.command == "ping":
            if not args.peer_id:
                raise SystemExit("Usage: tcppeer ping <peer-id>")
            _run_ping(config, args.peer_id)
        else:
            run_command(config, args.command, args.peer_id)
    except (OSError, ConfigurationError, sqlite3.Error) as exc:
        raise SystemExit(f"Cannot read TCPeer state: {exc}") from exc


if __name__ == "__main__":
    main()
