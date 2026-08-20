"""TCPeer control-plane coordinator."""

from __future__ import annotations

import argparse
import asyncio
from dataclasses import dataclass, field
import logging
import ipaddress
from pathlib import Path
import socket
import secrets
import sys
import time
from typing import Any

if __package__ in {None, ""}:
    sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from tcppeer.config import CoordinatorConfig, ConfigurationError
from tcppeer.auth import proof_matches
from tcppeer.protocol import ControlMessage, ProtocolError, read_control
from tcppeer.transport import is_usable_ipv6

LOG = logging.getLogger("tcppeer.coordinator")


@dataclass
class RegisteredPeer:
    network: str
    peer_id: str
    writer: Any
    observed_address: str
    observed_port: int
    declared_ipv4: str | None = None
    declared_ipv6: str | None = None
    mapped_ipv4_port: int | None = None
    mapped_ipv6_port: int | None = None
    local_ipv4: str | None = None
    local_ipv6: str | None = None
    listen_port: int | None = None
    role: str = "Client"
    platform: str = "Unknown"
    ready_for: set[str] = field(default_factory=set)
    connected_at: float = field(default_factory=time.monotonic)


@dataclass
class KnownPeer:
    network: str
    peer_id: str
    online: bool = True
    role: str = "Client"
    platform: str = "Unknown"
    ipv4: str = ""
    ipv6: str = ""
    overlay_ipv4: str = ""
    overlay_ipv6: str = ""
    transport: str = "None"
    endpoint: str = ""
    last_seen: int = field(default_factory=lambda: int(time.time()))


class Coordinator:
    """Authenticate and synchronize peers without accepting VPN DATA."""

    def __init__(self, config: CoordinatorConfig):
        self.config = config
        self.peers: dict[tuple[str, str], RegisteredPeer] = {}
        self.known_peers: dict[tuple[str, str], KnownPeer] = {}
        self.servers: list[asyncio.AbstractServer] = []
        self.admin_server: asyncio.AbstractServer | None = None

    async def start(self) -> None:
        await self._start_admin_listener()
        if self.config.listen_ipv6:
            self.servers.append(await asyncio.start_server(
                self.handle_client, self.config.listen_ipv6, self.config.port,
                family=socket.AF_INET6,
            ))
        if self.config.listen_ipv4:
            try:
                self.servers.append(await asyncio.start_server(
                    self.handle_client, self.config.listen_ipv4, self.config.port,
                    family=socket.AF_INET,
                ))
            except OSError:
                await self.close()
                raise

    async def serve_forever(self) -> None:
        await self.start()
        await asyncio.gather(*(server.serve_forever() for server in self.servers))

    async def close(self) -> None:
        if self.admin_server is not None:
            self.admin_server.close()
            await self.admin_server.wait_closed()
            self.admin_server = None
            Path("/run/tcppeer/coordinator-admin.sock").unlink(missing_ok=True)

        for server in self.servers:
            server.close()
        await asyncio.gather(*(server.wait_closed() for server in self.servers), return_exceptions=True)
        self.servers.clear()

    async def send(self, writer, command: str, **fields: str) -> None:
        writer.write(ControlMessage(command, fields).encode())
        await writer.drain()

    async def handle_client(self, reader, writer) -> None:
        peer: RegisteredPeer | None = None
        endpoint = writer.get_extra_info("peername") or ("unknown", 0)
        observed_address, observed_port = str(endpoint[0]), int(endpoint[1])
        try:
            auth = await read_control(reader, self.config.max_message_size)
            if auth.command == "ENDPOINT-QUERY":
                await self.send(writer, "ENDPOINT-INFO", Address=observed_address, Port=str(observed_port))
                return
            if auth.command != "AUTH":
                raise ProtocolError("AUTH must be the first command")
            network = auth.get("Network") or ""
            peer_id = auth.get("Peer-ID") or ""
            secret = self.config.networks.get(network)
            if not peer_id or secret is None:
                await self.send(writer, "AUTH-ERROR", Reason="invalid credentials")
                return
            nonce = secrets.token_hex(32)
            await self.send(writer, "AUTH-CHALLENGE", Nonce=nonce, Algorithm="HMAC-SHA256")
            proof = await read_control(reader, self.config.max_message_size)
            if proof.command != "AUTH-PROOF" or not proof_matches(
                secret, network, peer_id, nonce, proof.get("Proof") or "",
            ):
                await self.send(writer, "AUTH-ERROR", Reason="invalid credentials")
                return
            key = (network, peer_id)
            old = self.peers.get(key)
            if old is not None:
                await self.send(old.writer, "DISCONNECT", Reason="replaced by a new connection")
                old.writer.close()
            peer = RegisteredPeer(network, peer_id, writer, observed_address, observed_port)
            self.peers[key] = peer
            self.known_peers[key] = KnownPeer(
                network=network,
                peer_id=peer_id,
                endpoint=f"{observed_address}:{observed_port}",
                transport="TCP6" if ipaddress.ip_address(observed_address).version == 6 else "TCP4",
            )
            LOG.info("Peer %s authenticated on network %s from %s", peer_id, network, observed_address)
            await self.send(writer, "AUTH-OK", **{"Peer-ID": peer_id})
            await self.send(writer, "ENDPOINT-INFO", Address=observed_address, Port=str(observed_port))
            while True:
                message = await read_control(reader, self.config.max_message_size)
                await self.handle_message(peer, message)
        except (ProtocolError, asyncio.IncompleteReadError) as exc:
            if not writer.is_closing():
                try:
                    await self.send(writer, "ERROR", Reason=str(exc))
                except (ConnectionError, ProtocolError):
                    pass
        except ConnectionError:
            pass
        finally:
            if peer is not None and self.peers.get((peer.network, peer.peer_id)) is peer:
                del self.peers[(peer.network, peer.peer_id)]
                known = self.known_peers.get((peer.network, peer.peer_id))
                if known is not None:
                    known.online = False
                    known.last_seen = int(time.time())
                LOG.info("Peer %s disconnected from network %s", peer.peer_id, peer.network)
            writer.close()
            try:
                await writer.wait_closed()
            except ConnectionError:
                pass

    async def handle_message(self, peer: RegisteredPeer, message: ControlMessage) -> None:
        if message.command == "REGISTER":
            peer.declared_ipv4 = message.get("IPv4")
            peer.declared_ipv6 = message.get("IPv6")
            mapped_ipv4_port = message.get("Mapped-IPv4-Port")
            mapped_ipv6_port = message.get("Mapped-IPv6-Port")
            peer.mapped_ipv4_port = int(mapped_ipv4_port) if mapped_ipv4_port else None
            peer.mapped_ipv6_port = int(mapped_ipv6_port) if mapped_ipv6_port else None
            peer.local_ipv4 = message.get("Local-IPv4")
            peer.local_ipv6 = message.get("Local-IPv6")
            port = message.get("Port")
            peer.listen_port = int(port) if port else peer.observed_port
            peer.role = message.get("Role") or "Client"
            peer.platform = message.get("Platform") or "Unknown"
            known = self.known_peers[(peer.network, peer.peer_id)]
            known.role = peer.role
            known.platform = peer.platform
            known.ipv4 = peer.declared_ipv4 or ""
            known.ipv6 = peer.declared_ipv6 or ""
            known.overlay_ipv4 = message.get("Overlay-IPv4") or known.overlay_ipv4
            known.overlay_ipv6 = message.get("Overlay-IPv6") or known.overlay_ipv6
            known.online = True
            known.last_seen = int(time.time())
            await self.send(peer.writer, "ENDPOINT-INFO", Address=peer.observed_address, Port=str(peer.observed_port))
        elif message.command == "PEER-INFO" and message.get("Action") == "List":
            await self._send_device_list(peer)
        elif message.command == "PEER-INFO" and message.get("Action") == "Client-Error":
            LOG.warning("Peer %s client error: %s", peer.peer_id, message.get("Detail") or "unspecified")
        elif message.command == "PEER-INFO" and message.get("Action") == "Overlay-Update":
            known = self.known_peers[(peer.network, peer.peer_id)]
            known.overlay_ipv4 = message.get("Overlay-IPv4") or ""
            known.overlay_ipv6 = message.get("Overlay-IPv6") or ""
            known.last_seen = int(time.time())
        elif message.command in {"PING", "KEEPALIVE"}:
            await self.send(peer.writer, "PONG")
        elif message.command == "PUNCH-READY":
            target_id = message.get("Peer-ID") or ""
            peer.ready_for.add(target_id)
            target = self.peers.get((peer.network, target_id))
            if target is None:
                await self.send(peer.writer, "ERROR", Reason="requested peer is unavailable")
                return
            if peer.peer_id not in target.ready_for:
                await self.send(target.writer, "PEER-INFO", **{
                    "Peer-ID": peer.peer_id,
                    "Action": "Punch-Request",
                })
            if peer.peer_id in target.ready_for:
                await self._punch_go(peer, target)
        elif message.command == "DISCONNECT":
            peer.writer.close()
        else:
            await self.send(peer.writer, "ERROR", Reason=f"unexpected command: {message.command}")

    async def _start_admin_listener(self) -> None:
        admin_socket = Path("/run/tcppeer/coordinator-admin.sock")
        admin_socket.parent.mkdir(parents=True, exist_ok=True)
        admin_socket.unlink(missing_ok=True)

        self.admin_server = await asyncio.start_unix_server(
            self._handle_admin_client,
            path=admin_socket,
        )
        admin_socket.chmod(0o660)
        LOG.info("Coordinator admin socket listening on %s", admin_socket)

    async def _handle_admin_client(self, reader, writer) -> None:
        try:
            line = (
                await asyncio.wait_for(reader.readline(), timeout=5)
            ).decode("ascii").strip()

            parts = line.split()

            if len(parts) != 3 or parts[0] != "DELETE":
                writer.write(
                    b"ERROR expected: DELETE <network> <peer-id>\\n"
                )
                await writer.drain()
                return

            _command, network, peer_id = parts

            if any(ord(char) > 127 for char in network + peer_id):
                writer.write(b"ERROR invalid network or peer-id\\n")
                await writer.drain()
                return

            await self._delete_client_direct(network, peer_id)

            writer.write(
                f"OK deleted {peer_id} from {network}\\n".encode("ascii")
            )
            await writer.drain()

        except (TimeoutError, UnicodeError, ConnectionError):
            pass
        finally:
            writer.close()
            await writer.wait_closed()

    async def _delete_client_direct(
        self,
        network: str,
        target_id: str,
    ) -> None:
        if not target_id:
            raise ValueError("invalid client deletion target")

        target = self.peers.get((network, target_id))

        if target is not None:
            await self.send(
                target.writer,
                "DISCONNECT",
                Reason="deleted by coordinator administrator",
            )
            target.writer.close()

        self.known_peers.pop((network, target_id), None)

        LOG.info(
            "Coordinator administrator deleted peer %s from %s",
            target_id,
            network,
        )

    async def _send_device_list(self, peer: RegisteredPeer) -> None:
        devices = sorted(
            (known for known in self.known_peers.values() if known.network == peer.network),
            key=lambda known: (not known.online, known.peer_id),
        )
        for known in devices:
            await self.send(peer.writer, "PEER-INFO", **{
                "Action": "Device",
                "Peer-ID": known.peer_id,
                "Online": "yes" if known.online else "no",
                "Role": known.role,
                "Platform": known.platform,
                "Transport": known.transport,
                "IPv4": known.ipv4,
                "IPv6": known.ipv6,
                "Overlay-IPv4": known.overlay_ipv4,
                "Overlay-IPv6": known.overlay_ipv6,
                "Endpoint": known.endpoint,
                "Last-Seen": str(known.last_seen),
            })
        await self.send(peer.writer, "PEER-INFO", Action="List-End")

    async def _punch_go(self, left: RegisteredPeer, right: RegisteredPeer) -> None:
        same_public_origin = left.observed_address == right.observed_address
        local_ipv6_path = same_public_origin and self._same_local_network(left.local_ipv6, right.local_ipv6, 6)
        both_ipv6 = (
            is_usable_ipv6(left.declared_ipv6) and is_usable_ipv6(right.declared_ipv6)
        ) or local_ipv6_path
        family = "IPv6" if both_ipv6 else "IPv4"
        transport = "TCP6" if both_ipv6 else "TCP4"
        for current in (left, right):
            known = self.known_peers.get((current.network, current.peer_id))
            if known is not None:
                known.transport = transport
                known.last_seen = int(time.time())
        left_observed_version = ipaddress.ip_address(left.observed_address).version
        right_observed_version = ipaddress.ip_address(right.observed_address).version
        left_address = left.observed_address if left_observed_version == (6 if both_ipv6 else 4) else (left.declared_ipv6 if both_ipv6 else left.declared_ipv4)
        right_address = right.observed_address if right_observed_version == (6 if both_ipv6 else 4) else (right.declared_ipv6 if both_ipv6 else right.declared_ipv4)
        # IPv6 does not need a translated NAT port: peers dial the explicitly
        # registered passive listener. The control connection may use an
        # unrelated ephemeral source port (notably on Android).
        if both_ipv6:
            left_port = left.mapped_ipv6_port or left.listen_port
            right_port = right.mapped_ipv6_port or right.listen_port
        else:
            left_port = left.mapped_ipv4_port or (left.observed_port if left_observed_version == 4 else left.listen_port)
            right_port = right.mapped_ipv4_port or (right.observed_port if right_observed_version == 4 else right.listen_port)
        local_left = left.local_ipv6 if both_ipv6 else left.local_ipv4
        local_right = right.local_ipv6 if both_ipv6 else right.local_ipv4
        if same_public_origin and self._same_local_network(local_left, local_right, 6 if both_ipv6 else 4):
            left_address = local_left
            right_address = local_right
            left_port = left.listen_port
            right_port = right.listen_port
            LOG.info(
                "Using local TCP%s candidates for peers %s and %s",
                6 if both_ipv6 else 4, left.peer_id, right.peer_id,
            )
        if not left_address or not right_address or not left_port or not right_port:
            await self.send(left.writer, "ERROR", Reason="no endpoint for the required address family")
            await self.send(right.writer, "ERROR", Reason="no endpoint for the required address family")
            return
        start = str(int(time.time() * 1000) + 500)
        await self.send(left.writer, "PUNCH-GO", **{
            "Peer-ID": right.peer_id, "Address": str(right_address),
            "Port": str(right_port), "Family": family, "Start-Ms": start,
        })
        await self.send(right.writer, "PUNCH-GO", **{
            "Peer-ID": left.peer_id, "Address": str(left_address),
            "Port": str(left_port), "Family": family, "Start-Ms": start,
        })
        left.ready_for.discard(right.peer_id)
        right.ready_for.discard(left.peer_id)

    @staticmethod
    def _same_local_network(left: str | None, right: str | None, version: int) -> bool:
        if not left or not right:
            return False
        try:
            left_address = ipaddress.ip_address(left.split("%", 1)[0])
            right_address = ipaddress.ip_address(right.split("%", 1)[0])
        except ValueError:
            return False
        if left_address.version != version or right_address.version != version:
            return False
        prefix = 64 if version == 6 else 16
        return ipaddress.ip_network(f"{left_address}/{prefix}", strict=False) == ipaddress.ip_network(
            f"{right_address}/{prefix}", strict=False,
        )


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Run the TCPeer control-plane coordinator")
    parser.add_argument("--config", default="/etc/tcppeer/coordinator.toml")
    return parser


def main() -> None:
    args = build_parser().parse_args()
    try:
        config = CoordinatorConfig.from_file(args.config)
    except (OSError, ConfigurationError) as exc:
        raise SystemExit(f"Configuration error: {exc}") from exc
    logging.basicConfig(level=config.log_level, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
    try:
        asyncio.run(Coordinator(config).serve_forever())
    except KeyboardInterrupt:
        LOG.info("Coordinator stopped")


if __name__ == "__main__":
    main()
