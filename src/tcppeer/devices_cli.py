"""Administration CLI for the coordinator's persistent device directory."""

from __future__ import annotations

import argparse
import asyncio
from datetime import datetime
from pathlib import Path
import sys

DEFAULT_SOCKET = Path("/run/tcppeer/coordinator-admin.sock")


async def request(command: str, socket_path: Path) -> list[str]:
    try:
        reader, writer = await asyncio.open_unix_connection(socket_path)
    except OSError as exc:
        raise RuntimeError(f"cannot contact the coordinator at {socket_path}: {exc}") from exc
    try:
        writer.write((command + "\n").encode("ascii"))
        await writer.drain()
        lines: list[str] = []
        while True:
            raw = await reader.readline()
            if not raw:
                break
            line = raw.decode("ascii").rstrip("\n")
            if line == "OK":
                return lines
            if line.startswith("OK "):
                lines.append(line)
                return lines
            if line.startswith("ERROR "):
                raise RuntimeError(line[6:])
            lines.append(line)
        raise RuntimeError("coordinator closed the admin connection without a result")
    finally:
        writer.close()
        await writer.wait_closed()


def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser(description="Manage devices persisted by the TCPeer coordinator")
    result.add_argument("--socket", type=Path, default=DEFAULT_SOCKET, help=argparse.SUPPRESS)
    commands = result.add_subparsers(dest="command", required=True)
    commands.add_parser("list", help="list all known devices")
    remove = commands.add_parser("remove", help="remove a known device by Peer-ID")
    remove.add_argument("peer_id", help="exact Peer-ID to remove")
    remove.add_argument("--network", help="PeerNet name, required only when the Peer-ID is ambiguous")
    remove.add_argument("--yes", action="store_true", help="do not ask for confirmation")
    return result


def main() -> None:
    args = parser().parse_args()
    try:
        if args.command == "list":
            lines = asyncio.run(request("LIST", args.socket))
            if not lines:
                print("No known devices.")
                return
            print("NETWORK\tPEER-ID\tSTATUS\tROLE\tPLATFORM\tTRANSPORT\tIPv4\tIPv6\tOVERLAY IPv4\tOVERLAY IPv6\tENDPOINT\tLAST SEEN")
            for line in lines:
                fields = line.split("\t")
                if fields and fields[0] == "DEVICE":
                    try:
                        fields[-1] = datetime.fromtimestamp(int(fields[-1])).astimezone().isoformat(timespec="seconds")
                    except (ValueError, OSError):
                        pass
                    print("\t".join(fields[1:]))
            return
        if not args.yes:
            answer = input(f"Remove device {args.peer_id!r} permanently? [y/N]: ").strip().casefold()
            if answer not in {"y", "yes"}:
                print("Cancelled.")
                return
        command = f"DELETE {args.network} {args.peer_id}" if args.network else f"DELETE-ID {args.peer_id}"
        for line in asyncio.run(request(command, args.socket)):
            print(line[3:] if line.startswith("OK ") else line)
    except (RuntimeError, UnicodeError) as exc:
        raise SystemExit(f"tcppeer-devices: {exc}") from exc


if __name__ == "__main__":
    main()
