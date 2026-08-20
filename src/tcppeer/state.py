"""SQLite persistence for TCPeer server state."""

from __future__ import annotations

from contextlib import contextmanager
from dataclasses import dataclass
import ipaddress
from pathlib import Path
import sqlite3
import time
from typing import Iterator


SCHEMA = """
CREATE TABLE IF NOT EXISTS metadata (
    key TEXT PRIMARY KEY,
    value TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS peers (
    peer_id TEXT PRIMARY KEY,
    overlay_ipv4 TEXT,
    overlay_ipv6 TEXT,
    transport TEXT NOT NULL DEFAULT 'Disconnected',
    endpoint TEXT,
    rx_bytes INTEGER NOT NULL DEFAULT 0,
    tx_bytes INTEGER NOT NULL DEFAULT 0,
    connected_at INTEGER,
    updated_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS sessions (
    session_id TEXT PRIMARY KEY,
    peer_id TEXT NOT NULL,
    family INTEGER NOT NULL,
    endpoint TEXT NOT NULL,
    state TEXT NOT NULL,
    started_at INTEGER NOT NULL,
    ended_at INTEGER,
    FOREIGN KEY(peer_id) REFERENCES peers(peer_id)
);
CREATE TABLE IF NOT EXISTS leases (
    client_id TEXT PRIMARY KEY,
    address TEXT NOT NULL UNIQUE,
    starts_at INTEGER NOT NULL,
    expires_at INTEGER NOT NULL,
    state TEXT NOT NULL CHECK(state IN ('offered', 'active'))
);
"""


@dataclass(frozen=True)
class Lease:
    client_id: str
    address: ipaddress.IPv4Address
    starts_at: int
    expires_at: int
    state: str


class StateStore:
    """Own persistent state and transactional IPv4 lease allocation."""

    def __init__(self, path: str | Path):
        self.path = Path(path)
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self.connection = sqlite3.connect(self.path)
        self.connection.row_factory = sqlite3.Row
        self.connection.execute("PRAGMA foreign_keys = ON")
        self.connection.execute("PRAGMA journal_mode = WAL")
        self.connection.executescript(SCHEMA)
        self.connection.commit()

    def close(self) -> None:
        self.connection.close()

    @contextmanager
    def immediate(self) -> Iterator[sqlite3.Connection]:
        self.connection.execute("BEGIN IMMEDIATE")
        try:
            yield self.connection
        except Exception:
            self.connection.rollback()
            raise
        else:
            self.connection.commit()

    def expire_leases(self, now: int | None = None) -> int:
        timestamp = int(time.time()) if now is None else now
        with self.connection:
            cursor = self.connection.execute("DELETE FROM leases WHERE expires_at <= ?", (timestamp,))
        return cursor.rowcount

    def allocate_lease(
        self,
        client_id: str,
        pool_start: ipaddress.IPv4Address,
        pool_end: ipaddress.IPv4Address,
        lease_seconds: int,
        requested: ipaddress.IPv4Address | None = None,
        offered: bool = False,
        now: int | None = None,
    ) -> Lease:
        timestamp = int(time.time()) if now is None else now
        state = "offered" if offered else "active"
        with self.immediate() as db:
            db.execute("DELETE FROM leases WHERE expires_at <= ?", (timestamp,))
            existing = db.execute("SELECT * FROM leases WHERE client_id = ?", (client_id,)).fetchone()
            if existing is not None:
                address = ipaddress.ip_address(existing["address"])
            else:
                used = {int(ipaddress.ip_address(row[0])) for row in db.execute("SELECT address FROM leases")}
                candidates: list[ipaddress.IPv4Address] = []
                if requested is not None and int(pool_start) <= int(requested) <= int(pool_end):
                    candidates.append(requested)
                candidates.extend(
                    ipaddress.ip_address(number)
                    for number in range(int(pool_start), int(pool_end) + 1)
                    if requested is None or number != int(requested)
                )
                address = next((item for item in candidates if int(item) not in used), None)
                if address is None:
                    raise RuntimeError("DHCP address pool is exhausted")
            expires_at = timestamp + lease_seconds
            db.execute(
                """INSERT INTO leases(client_id, address, starts_at, expires_at, state)
                   VALUES(?, ?, ?, ?, ?)
                   ON CONFLICT(client_id) DO UPDATE SET
                     address=excluded.address, starts_at=excluded.starts_at,
                     expires_at=excluded.expires_at, state=excluded.state""",
                (client_id, str(address), timestamp, expires_at, state),
            )
        return Lease(client_id, address, timestamp, expires_at, state)

    def activate_lease(self, client_id: str, lease_seconds: int, now: int | None = None) -> Lease | None:
        timestamp = int(time.time()) if now is None else now
        expires_at = timestamp + lease_seconds
        with self.connection:
            self.connection.execute(
                "UPDATE leases SET starts_at=?, expires_at=?, state='active' WHERE client_id=?",
                (timestamp, expires_at, client_id),
            )
        return self.get_lease(client_id)

    def get_lease(self, client_id: str) -> Lease | None:
        row = self.connection.execute("SELECT * FROM leases WHERE client_id = ?", (client_id,)).fetchone()
        if row is None:
            return None
        return Lease(row["client_id"], ipaddress.ip_address(row["address"]), row["starts_at"], row["expires_at"], row["state"])

    def release_lease(self, client_id: str) -> bool:
        with self.connection:
            cursor = self.connection.execute("DELETE FROM leases WHERE client_id = ?", (client_id,))
        return cursor.rowcount > 0

    def delete_client(self, peer_id: str) -> None:
        with self.connection:
            self.connection.execute("DELETE FROM leases WHERE client_id = ?", (peer_id,))
            self.connection.execute("DELETE FROM sessions WHERE peer_id = ?", (peer_id,))
            self.connection.execute("DELETE FROM peers WHERE peer_id = ?", (peer_id,))

    def list_table(self, table: str) -> list[sqlite3.Row]:
        if table not in {"peers", "sessions", "leases", "metadata"}:
            raise ValueError("unsupported state table")
        return list(self.connection.execute(f"SELECT * FROM {table}"))

    def update_peer(self, peer_id: str, **values: object) -> None:
        allowed = {"overlay_ipv4", "overlay_ipv6", "transport", "endpoint", "rx_bytes", "tx_bytes", "connected_at"}
        unknown = set(values) - allowed
        if unknown:
            raise ValueError(f"unsupported peer fields: {', '.join(sorted(unknown))}")
        now = int(time.time())
        with self.connection:
            self.connection.execute(
                "INSERT INTO peers(peer_id, updated_at) VALUES(?, ?) ON CONFLICT(peer_id) DO NOTHING",
                (peer_id, now),
            )
            if values:
                assignments = ", ".join(f"{key} = ?" for key in values)
                self.connection.execute(
                    f"UPDATE peers SET {assignments}, updated_at = ? WHERE peer_id = ?",
                    (*values.values(), now, peer_id),
                )
