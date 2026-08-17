"""Persistent PeerNet Hosting access control."""

from __future__ import annotations

from pathlib import Path
import sqlite3
import time


class HostingStore:
    def __init__(self, path: Path):
        path.parent.mkdir(parents=True, exist_ok=True)
        self.connection = sqlite3.connect(path)
        self.connection.execute(
            """CREATE TABLE IF NOT EXISTS revoked_clients(
                   network TEXT NOT NULL,
                   peer_id TEXT NOT NULL,
                   revoked_at INTEGER NOT NULL,
                   PRIMARY KEY(network, peer_id)
               )"""
        )
        self.connection.commit()

    def is_revoked(self, network: str, peer_id: str) -> bool:
        return self.connection.execute(
            "SELECT 1 FROM revoked_clients WHERE network=? AND peer_id=?", (network, peer_id),
        ).fetchone() is not None

    def revoke(self, network: str, peer_id: str) -> None:
        with self.connection:
            self.connection.execute(
                "INSERT OR REPLACE INTO revoked_clients(network, peer_id, revoked_at) VALUES(?, ?, ?)",
                (network, peer_id, int(time.time())),
            )

    def close(self) -> None:
        self.connection.close()
