"""Persistent coordinator device directory."""

from __future__ import annotations

from pathlib import Path
import sqlite3


SCHEMA = """
CREATE TABLE IF NOT EXISTS known_peers (
    network TEXT NOT NULL,
    peer_id TEXT NOT NULL,
    role TEXT NOT NULL DEFAULT 'Client',
    platform TEXT NOT NULL DEFAULT 'Unknown',
    transport TEXT NOT NULL DEFAULT 'None',
    ipv4 TEXT NOT NULL DEFAULT '',
    ipv6 TEXT NOT NULL DEFAULT '',
    overlay_ipv4 TEXT NOT NULL DEFAULT '',
    overlay_ipv6 TEXT NOT NULL DEFAULT '',
    endpoint TEXT NOT NULL DEFAULT '',
    last_seen INTEGER NOT NULL,
    PRIMARY KEY (network, peer_id)
);
"""


class CoordinatorStore:
    """SQLite-backed inventory; online state intentionally remains ephemeral."""

    FIELDS = (
        "network", "peer_id", "role", "platform", "transport", "ipv4", "ipv6",
        "overlay_ipv4", "overlay_ipv6", "endpoint", "last_seen",
    )

    def __init__(self, path: str | Path):
        self.path = Path(path)
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self.connection = sqlite3.connect(self.path)
        self.connection.row_factory = sqlite3.Row
        self.connection.execute("PRAGMA journal_mode = WAL")
        self.connection.executescript(SCHEMA)
        self.connection.commit()

    def load(self) -> list[sqlite3.Row]:
        return list(self.connection.execute("SELECT * FROM known_peers ORDER BY network, peer_id"))

    def upsert(self, values: dict[str, object]) -> None:
        unknown = set(values) - set(self.FIELDS)
        if unknown or "network" not in values or "peer_id" not in values:
            raise ValueError("invalid persistent peer fields")
        columns = tuple(key for key in self.FIELDS if key in values)
        placeholders = ", ".join("?" for _ in columns)
        updates = ", ".join(f"{key}=excluded.{key}" for key in columns if key not in {"network", "peer_id"})
        sql = (
            f"INSERT INTO known_peers ({', '.join(columns)}) VALUES ({placeholders}) "
            f"ON CONFLICT(network, peer_id) DO UPDATE SET {updates}"
        )
        with self.connection:
            self.connection.execute(sql, tuple(values[key] for key in columns))

    def delete(self, network: str, peer_id: str) -> bool:
        with self.connection:
            cursor = self.connection.execute(
                "DELETE FROM known_peers WHERE network=? AND peer_id=?", (network, peer_id),
            )
        return cursor.rowcount > 0

    def close(self) -> None:
        self.connection.close()
