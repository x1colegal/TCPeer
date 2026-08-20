"""Read-only operational CLI for TCPeer server state."""

from __future__ import annotations

import argparse
import datetime as dt
from pathlib import Path
import sqlite3
import sys
import time

if __package__ in {None, ""}:
    sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from tcppeer.config import ConfigurationError, ServerConfig


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


def run_command(config: ServerConfig, command: str, peer_id: str | None = None) -> None:
    connection = sqlite3.connect(f"file:{config.state_db}?mode=ro", uri=True)
    try:
        if command in {"status", "peers", "addresses", "transport", "stats"}:
            peers = _rows(connection, "SELECT * FROM peers ORDER BY peer_id")
            if command == "status":
                connected = sum(row["transport"] not in {"Disconnected", "No Direct Connection"} for row in peers)
                print(f"Server peer ID: {config.peer_id}")
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


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Inspect TCPeer server state")
    parser.add_argument("--config", default="/etc/tcppeer/server.toml")
    parser.add_argument("command", choices=("status", "peers", "leases", "sessions", "addresses", "transport", "stats"))
    parser.add_argument("peer_id", nargs="?")
    return parser


def main() -> None:
    args = build_parser().parse_args()
    try:
        config = ServerConfig.from_file(args.config)
        run_command(config, args.command, args.peer_id)
    except (OSError, ConfigurationError, sqlite3.Error) as exc:
        raise SystemExit(f"Cannot read TCPeer state: {exc}") from exc


if __name__ == "__main__":
    main()
