"""Stateful TCPeer Linux server."""

from __future__ import annotations

import argparse
import asyncio
import fcntl
import ipaddress
import logging
import os
from pathlib import Path
import socket
import struct
import sys
import time
import uuid

if __package__ in {None, ""}:
    sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from tcppeer.config import ConfigurationError, ServerConfig
from tcppeer.auth import authentication_proof
from tcppeer.dhcp import DhcpServer
from tcppeer.dns import discover_upstream_dns
from tcppeer.exit_node import ExitNodeFirewall
from tcppeer.packet import build_dhcp_packet, extract_dhcp_payload
from tcppeer.protocol import ControlMessage, ProtocolError, encode_data, read_control, read_data
from tcppeer.ra import ALL_NODES, LINK_LOCAL_ROUTER, build_router_advertisement, ipv6_source, is_router_solicitation
from tcppeer.pd import PrefixDelegationClient, discover_ipv6_upstream, router_address, slaac_subnet
from tcppeer.state import StateStore
from tcppeer.tpp import ECHO_REQUEST, ECHO_REPLY, build_tpp, build_reply as build_tpp_reply, parse_tpp
from tcppeer.transport import (
    DirectConnectionError,
    DirectConnector,
    Endpoint,
    choose_family,
    is_usable_ipv6,
    resolve_tcp_endpoints,
)
from tcppeer.tun import TunDevice

LOG = logging.getLogger("tcppeer.server")


def public_address(address: str | None) -> str | None:
    if not address:
        return None
    try:
        parsed = ipaddress.ip_address(address.split("%", 1)[0])
    except ValueError:
        return None
    return str(parsed) if parsed.is_global else None


def discover_direct_ipv4(excluded_interfaces: set[str] | None = None) -> str | None:
    """Find an active non-loopback IPv4 address using TCP socket ioctls."""
    excluded = excluded_interfaces or set()
    probe = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    try:
        for _index, name in socket.if_nameindex():
            if name in excluded or name.startswith(("lo", "tun", "tap", "tailscale", "docker", "veth")):
                continue
            request = struct.pack("256s", name.encode("ascii", "ignore")[:15])
            try:
                flags = struct.unpack("H", fcntl.ioctl(probe.fileno(), 0x8913, request)[16:18])[0]
                if not flags & 0x1 or not flags & 0x40:  # IFF_UP and IFF_RUNNING
                    continue
                packed = fcntl.ioctl(probe.fileno(), 0x8915, request)[20:24]
            except OSError:
                continue
            address = socket.inet_ntoa(packed)
            parsed = ipaddress.ip_address(address)
            if not parsed.is_loopback and not parsed.is_link_local and not parsed.is_unspecified:
                return address
    finally:
        probe.close()
    return None


class Server:
    """Own the TUN device, address services, state, and direct peer streams."""

    def __init__(self, config: ServerConfig):
        self.config = config
        self.store = StateStore(config.state_db)
        self.dns = config.dns or discover_upstream_dns({config.tun_name})
        self.dhcp = DhcpServer(
            self.store, config.ipv4_subnet, config.server_ipv4,
            config.pool_start, config.pool_end, config.lease_seconds, self.dns,
        )
        self.tun = TunDevice(config.tun_name, config.mtu)
        self.direct_writer = None
        self.direct_peer_id: str | None = None
        self._tasks: set[asyncio.Task] = set()
        self._listeners: list[asyncio.AbstractServer] = []
        self._direct_bind_ipv4 = config.direct_ipv4 or discover_direct_ipv4({config.tun_name})
        self._direct_bind_ipv6 = config.direct_ipv6
        self._registered_ipv4 = public_address(self._direct_bind_ipv4)
        self._registered_ipv6 = public_address(config.direct_ipv6)
        self._registered_port_ipv4: int | None = config.direct_port if self._registered_ipv4 else None
        self._registered_port_ipv6: int | None = config.direct_port if self._registered_ipv6 else None
        self._direct_candidates: dict[socket.AddressFamily, socket.socket] = {}
        self._direct_connect_tasks: dict[str, asyncio.Task] = {}
        self._direct_adoption_lock = asyncio.Lock()
        self._direct_owner_token: str | None = None
        self._direct_owner_key: tuple[str, str] | None = None
        self._direct_attempt_counter = 0
        self._byte_counters: dict[tuple[str, str], int] = {}
        self._coordinator_writer = None
        self._pending_tpp_pings: dict[int, asyncio.Future] = {}
        self.exit_node = ExitNodeFirewall(config, config.tun_name)
        self._active_ipv6_prefix = config.ipv6_prefix
        self._active_server_ipv6 = config.server_ipv6
        self._pd_active = False
        self._pd_interface: str | None = None

    async def run(self) -> None:
        self.tun.open()

        await self._configure_initial_ipv6()

        self.tun.configure(
            str(self.config.server_ipv4), self.config.ipv4_subnet.prefixlen,
            str(self._active_server_ipv6), self._active_ipv6_prefix.prefixlen,
        )
        self.exit_node.interface = self.tun.name
        self.exit_node.apply()
        try:
            self._prepare_direct_candidates()
            await self._start_direct_listeners()
            await self._start_admin_listener()
            self._tasks = {
                asyncio.create_task(self._control_loop(), name="control"),
                asyncio.create_task(self._tun_loop(), name="tun"),
                asyncio.create_task(self._ra_loop(), name="router-advertisement"),
                asyncio.create_task(self._statistics_loop(), name="statistics"),
                asyncio.create_task(self._pd_loop(), name="prefix-delegation"),
            }
            done, pending = await asyncio.wait(self._tasks, return_when=asyncio.FIRST_EXCEPTION)
            for task in pending:
                task.cancel()
            for task in done:
                task.result()
        finally:
            for task in self._tasks:
                task.cancel()
            for listener in self._listeners:
                listener.close()
            for candidate in self._direct_candidates.values():
                candidate.close()
            self._direct_candidates.clear()
            await asyncio.gather(*(listener.wait_closed() for listener in self._listeners), return_exceptions=True)
            self.tun.close()
            self.exit_node.close()
            self._flush_byte_counters()
            self.store.close()

    def _prepare_direct_candidates(self) -> None:
        """Pre-bind ICE-TCP simultaneous-open candidates before listen()."""
        for family, address in (
            (socket.AF_INET6, self._direct_bind_ipv6 or "::"),
            (socket.AF_INET, self._direct_bind_ipv4 or "0.0.0.0"),
        ):
            candidate = socket.socket(family, socket.SOCK_STREAM, socket.IPPROTO_TCP)
            candidate.setblocking(False)
            candidate.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            if hasattr(socket, "SO_REUSEPORT"):
                candidate.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEPORT, 1)
            try:
                candidate.bind((address, self.config.direct_port))
            except OSError:
                candidate.close()
                continue
            self._direct_candidates[family] = candidate

    async def _start_direct_listeners(self) -> None:
        candidates = []
        if self.config.direct_ipv6:
            candidates.append((self.config.direct_ipv6, socket.AF_INET6))
        if self.config.direct_ipv4:
            candidates.append((self.config.direct_ipv4, socket.AF_INET))
        if not candidates:
            candidates = [("::", socket.AF_INET6), ("0.0.0.0", socket.AF_INET)]
        for address, family in candidates:
            listener = await asyncio.start_server(
                self._accept_direct, address, self.config.direct_port,
                family=family, reuse_port=True,
            )
            self._listeners.append(listener)

    async def _accept_direct(self, reader, writer) -> None:
        attempt = self._next_direct_attempt()
        try:
            direct_socket = writer.get_extra_info("socket")
            direct_socket.setsockopt(socket.SOL_SOCKET, socket.SO_SNDBUF, 4 * 1024 * 1024)
            direct_socket.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 4 * 1024 * 1024)
            LOG.info(
                "direct-accept start ts=%.6f attempt=%s initiated=no fd=%s local=%s remote=%s",
                time.time(),
                attempt,
                direct_socket.fileno(),
                self._sockname_text(writer),
                self._peername_text(writer),
            )
            info = await read_control(reader)
            if info.command != "PEER-INFO" or info.get("Network") != self.config.network:
                raise ProtocolError("invalid direct peer handshake")
            peer_id = info.get("Peer-ID") or "unknown"
            required_family = choose_family(
                [self._registered_ipv6 or ""], [info.get("IPv6") or ""],
            )
            actual_family = direct_socket.family
            if actual_family != required_family:
                raise ProtocolError("direct connection family violates IPv6-first policy")
            writer.write(ControlMessage("PEER-INFO", {
                "Network": self.config.network, "Peer-ID": self.config.peer_id,
                "IPv4": self._registered_ipv4 or "", "IPv6": self._registered_ipv6 or "",
            }).encode())
            await writer.drain()
            endpoint = writer.get_extra_info("peername") or ("unknown", 0)
            await self._adopt_direct(
                reader,
                writer,
                peer_id,
                actual_family,
                f"{endpoint[0]}:{endpoint[1]}",
                initiated=False,
                attempt=attempt,
            )
        except (ProtocolError, ConnectionError, asyncio.IncompleteReadError) as exc:
            LOG.warning("Rejected direct connection: %s", exc)
            writer.close()

    async def _control_loop(self) -> None:
        reader, writer = await self._open_coordinator_connection()
        self._coordinator_writer = writer
        writer.write(ControlMessage("AUTH", {
            "Network": self.config.network,
            "Peer-ID": self.config.peer_id,
        }).encode())
        await writer.drain()
        challenge = await read_control(reader)
        if challenge.command != "AUTH-CHALLENGE":
            raise ProtocolError(challenge.get("Reason", "coordinator did not issue an authentication challenge"))
        nonce = challenge.get("Nonce") or ""
        writer.write(ControlMessage("AUTH-PROOF", {
            "Proof": authentication_proof(self.config.secret, self.config.network, self.config.peer_id, nonce),
        }).encode())
        await writer.drain()
        response = await read_control(reader)
        if response.command != "AUTH-OK":
            raise ProtocolError(response.get("Reason", "authentication failed"))
        observed = await read_control(reader)
        if observed.command != "ENDPOINT-INFO":
            raise ProtocolError("coordinator did not report the observed endpoint")
        observed_address = observed.get("Address") or ""
        try:
            observed_version = ipaddress.ip_address(observed_address).version
        except ValueError:
            observed_version = 0
        if observed_version == 4:
            self._registered_ipv4 = observed_address
            self._registered_port_ipv4 = int(observed.get("Port") or self.config.direct_port)
        if observed_version == 6:
            self._registered_ipv6 = observed_address
            self._registered_port_ipv6 = int(observed.get("Port") or self.config.direct_port)
        other_family = socket.AF_INET if observed_version == 6 else socket.AF_INET6
        other_observed = await self._query_observed_endpoint(other_family)
        if other_observed:
            other_address, other_port = other_observed
            if other_family == socket.AF_INET:
                self._registered_ipv4 = other_address
                self._registered_port_ipv4 = other_port
            else:
                self._registered_ipv6 = other_address
                self._registered_port_ipv6 = other_port
        LOG.info(
            "Registering direct endpoints IPv4=%s IPv6=%s",
            self._registered_ipv4 or "none",
            self._registered_ipv6 or "none",
        )
        writer.write(ControlMessage("REGISTER", {
            "Peer-ID": self.config.peer_id,
            "IPv4": self._registered_ipv4 or "",
            "IPv6": self._registered_ipv6 or "",
            "Mapped-IPv4-Port": str(self._registered_port_ipv4 or ""),
            "Mapped-IPv6-Port": str(self._registered_port_ipv6 or ""),
            "Local-IPv4": self._direct_bind_ipv4 or "",
            "Local-IPv6": self._direct_bind_ipv6 or "",
            "Port": str(self.config.direct_port),
            "Role": "Exit-Node",
            "Platform": "Linux",
            "Overlay-IPv4": str(self.config.server_ipv4),
            "Overlay-IPv6": str(self._active_server_ipv6),
        }).encode())
        if self.config.target_peer:
            writer.write(ControlMessage("PUNCH-READY", {"Peer-ID": self.config.target_peer}).encode())
        await writer.drain()

        device_list_peers: set[str] = set()
        device_list_in_progress = False

        async def refresh_device_list() -> None:
            nonlocal device_list_in_progress
            while True:
                await asyncio.sleep(5)
                if device_list_in_progress:
                    continue
                device_list_peers.clear()
                device_list_in_progress = True
                writer.write(ControlMessage("PEER-INFO", {
                    "Action": "List",
                }).encode())
                await writer.drain()

        refresh_task = asyncio.create_task(
            refresh_device_list(),
            name="device-list-refresh",
        )
        self._tasks.add(refresh_task)
        refresh_task.add_done_callback(self._tasks.discard)

        while True:
            try:
                message = await asyncio.wait_for(read_control(reader), timeout=30)
            except TimeoutError:
                writer.write(ControlMessage("KEEPALIVE", {}).encode())
                await writer.drain()
                continue
            if message.command == "PING":
                writer.write(ControlMessage("PONG", {}).encode())
                await writer.drain()
            elif message.command == "PUNCH-GO":
                peer_id = message.get("Peer-ID") or "unknown"
                previous = self._direct_connect_tasks.get(peer_id)
                if previous is not None and not previous.done():
                    LOG.info("direct-connect cancel-stale ts=%.6f peer_id=%s reason=new-punch-go", time.time(), peer_id)
                    previous.cancel()
                task = asyncio.create_task(self._connect_direct(message), name=f"direct-connect:{peer_id}")
                self._direct_connect_tasks[peer_id] = task
                self._tasks.add(task)
                task.add_done_callback(self._tasks.discard)
                task.add_done_callback(lambda done, pid=peer_id: self._clear_direct_connect_task(pid, done))
            elif message.command == "PEER-INFO" and message.get("Action") == "Punch-Request":
                requested_peer = message.get("Peer-ID")
                if requested_peer:
                    writer.write(ControlMessage("PUNCH-READY", {"Peer-ID": requested_peer}).encode())
                    await writer.drain()
            elif message.command == "PEER-INFO" and message.get("Action") == "Device":
                peer_id = message.get("Peer-ID") or ""
                if peer_id:
                    device_list_peers.add(peer_id)
                    self.store.update_peer(
                        peer_id,
                        overlay_ipv4=message.get("Overlay-IPv4") or None,
                        overlay_ipv6=message.get("Overlay-IPv6") or None,
                        transport=message.get("Transport") or "Disconnected",
                        endpoint=message.get("Endpoint") or None,
                    )
            elif message.command == "PEER-INFO" and message.get("Action") == "List-End":
                for row in self.store.list_table("peers"):
                    peer_id = row["peer_id"]
                    if peer_id != self.config.peer_id and peer_id not in device_list_peers:
                        self.store.delete_client(peer_id)
                device_list_in_progress = False
            elif message.command == "ERROR":
                LOG.warning("Coordinator error: %s", message.get("Reason", "unspecified error"))
            elif message.command in {"DISCONNECT", "AUTH-ERROR"}:
                raise ConnectionError(message.get("Reason", "coordinator disconnected"))

    async def _query_observed_endpoint(self, family: socket.AddressFamily) -> tuple[str, int] | None:
        local_address = self._direct_bind_ipv6 if family == socket.AF_INET6 else self._direct_bind_ipv4
        if not local_address:
            return None
        results = await resolve_tcp_endpoints(self.config.coordinator_address, self.config.coordinator_port)
        loop = asyncio.get_running_loop()
        for candidate_family, socktype, protocol, _name, address in results:
            if candidate_family != family:
                continue
            sock = socket.socket(candidate_family, socktype, protocol)
            sock.setblocking(False)
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            if hasattr(socket, "SO_REUSEPORT"):
                sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEPORT, 1)
            try:
                sock.bind((local_address, self.config.direct_port))
                await asyncio.wait_for(loop.sock_connect(sock, address), timeout=5)
                reader, writer = await asyncio.open_connection(sock=sock)
                writer.write(ControlMessage("ENDPOINT-QUERY", {}).encode())
                await writer.drain()
                response = await asyncio.wait_for(read_control(reader), timeout=5)
                writer.close()
                await asyncio.gather(writer.wait_closed(), return_exceptions=True)
                if response.command == "ENDPOINT-INFO":
                    address_value = response.get("Address") or ""
                    port_value = int(response.get("Port") or 0)
                    if address_value and port_value:
                        return address_value, port_value
            except (OSError, TimeoutError, ProtocolError, asyncio.IncompleteReadError):
                sock.close()
        return None

    async def _open_coordinator_connection(self):
        """Use the direct local TCP port so the observed mapping is relevant."""
        loop = asyncio.get_running_loop()
        results = await resolve_tcp_endpoints(
            self.config.coordinator_address,
            self.config.coordinator_port,
        )
        # Try TCP6 first even when no address was configured explicitly. Binding
        # to :: lets the kernel select the usable local source address. The
        # coordinator's observed public address is advertised separately.
        preferred = [socket.AF_INET6, socket.AF_INET]
        last_error: OSError | None = None
        for family in preferred:
            local_address = self._direct_bind_ipv6 if family == socket.AF_INET6 else self._direct_bind_ipv4
            local_address = local_address or ("::" if family == socket.AF_INET6 else "0.0.0.0")
            for candidate_family, socktype, protocol, _name, address in results:
                if candidate_family != family:
                    continue
                sock = socket.socket(candidate_family, socktype, protocol)
                sock.setblocking(False)
                sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
                if hasattr(socket, "SO_REUSEPORT"):
                    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEPORT, 1)
                try:
                    sock.bind((local_address, self.config.direct_port))
                    await loop.sock_connect(sock, address)
                    selected_address = str(sock.getsockname()[0]).split("%", 1)[0]
                    if family == socket.AF_INET6 and is_usable_ipv6(selected_address):
                        self._direct_bind_ipv6 = selected_address
                        LOG.info("Automatically selected local IPv6 bind address %s", selected_address)
                    elif family == socket.AF_INET and selected_address != "0.0.0.0":
                        self._direct_bind_ipv4 = selected_address
                        self._registered_ipv4 = public_address(selected_address)
                    return await asyncio.open_connection(sock=sock)
                except OSError as exc:
                    last_error = exc
                    sock.close()
        raise ConnectionError("cannot establish the TCP coordinator connection") from last_error

    async def _connect_direct(self, message: ControlMessage) -> None:
        family_text = message.get("Family")
        family = socket.AF_INET6 if family_text == "IPv6" else socket.AF_INET
        local_address = self._direct_bind_ipv6 if family == socket.AF_INET6 else self._direct_bind_ipv4
        if not local_address:
            local_address = "::" if family == socket.AF_INET6 else "0.0.0.0"
        remote = Endpoint(message.get("Address") or "", int(message.get("Port") or 0), family)
        local = Endpoint(local_address, self.config.direct_port, family)
        start_ms = int(message.get("Start-Ms") or 0)
        delay = start_ms / 1000 - time.time()
        if delay > 0:
            await asyncio.sleep(delay)
        peer_id = message.get("Peer-ID") or "unknown"
        attempt = self._next_direct_attempt()
        try:
            candidate = self._direct_candidates.pop(family, None)
            LOG.info(
                "direct-connect attempt ts=%.6f peer_id=%s family=%s attempt=%s initiated=yes local=%s:%s remote=%s:%s candidate_fd=%s",
                time.time(),
                peer_id,
                "tcp6" if family == socket.AF_INET6 else "tcp4",
                attempt,
                local.address,
                local.port,
                remote.address,
                remote.port,
                candidate.fileno() if candidate is not None else None,
            )
            reader, writer = await DirectConnector().connect(
                local, remote, family, prebound_socket=candidate, peer_id=peer_id, attempt=attempt,
            )
        except asyncio.CancelledError:
            LOG.info(
                "direct-connect cancelled ts=%.6f peer_id=%s family=%s attempt=%s initiated=yes",
                time.time(),
                peer_id,
                "tcp6" if family == socket.AF_INET6 else "tcp4",
                attempt,
            )
            raise
        except DirectConnectionError:
            self.store.update_peer(peer_id, transport="No Direct Connection")
            raise
        writer.write(ControlMessage("PEER-INFO", {
            "Network": self.config.network, "Peer-ID": self.config.peer_id,
            "IPv4": self._registered_ipv4 or "", "IPv6": self._registered_ipv6 or "",
        }).encode())
        await writer.drain()
        peer_info = await read_control(reader)
        if peer_info.command != "PEER-INFO" or peer_info.get("Network") != self.config.network:
            writer.close()
            raise ProtocolError("invalid direct peer handshake")
        required_family = choose_family(
            [self._registered_ipv6 or ""], [peer_info.get("IPv6") or ""],
        )
        if required_family != family:
            writer.close()
            raise ProtocolError("coordinator selected a family that violates IPv6-first policy")
        await self._adopt_direct(
            reader,
            writer,
            peer_id,
            family,
            f"{remote.address}:{remote.port}",
            initiated=True,
            attempt=attempt,
        )

    async def _adopt_direct(
        self,
        reader,
        writer,
        peer_id: str,
        family: socket.AddressFamily,
        endpoint: str,
        *,
        initiated: bool,
        attempt: int,
    ) -> None:
        token = str(uuid.uuid4())
        connection_key = self._connection_key(writer)
        replaced_writer = None
        replaced_peer_id = None
        packet_count = 0

        async with self._direct_adoption_lock:
            if self.direct_writer is not None and self.direct_peer_id == peer_id and self._direct_owner_key is not None:
                if connection_key >= self._direct_owner_key:
                    LOG.info(
                        "direct-adopt loser-close ts=%.6f peer_id=%s family=%s attempt=%s initiated=%s fd=%s local=%s remote=%s winner_key=%s loser_key=%s",
                        time.time(),
                        peer_id,
                        "tcp6" if family == socket.AF_INET6 else "tcp4",
                        attempt,
                        "yes" if initiated else "no",
                        self._socket_fd(writer),
                        self._sockname_text(writer),
                        self._peername_text(writer),
                        self._direct_owner_key,
                        connection_key,
                    )
                    writer.close()
                    await asyncio.gather(writer.wait_closed(), return_exceptions=True)
                    return
            replaced_writer = self.direct_writer
            replaced_peer_id = self.direct_peer_id
            self.direct_writer = writer
            self.direct_peer_id = peer_id
            self._direct_owner_token = token
            self._direct_owner_key = connection_key

        if replaced_writer is not None and replaced_writer is not writer:
            LOG.info(
                "direct-adopt replace-close ts=%.6f old_peer_id=%s new_peer_id=%s attempt=%s reason=replaced-by-new-direct fd=%s",
                time.time(),
                replaced_peer_id,
                peer_id,
                attempt,
                self._socket_fd(replaced_writer),
            )
            replaced_writer.close()

        label = "TCP6 Direct" if family == socket.AF_INET6 else "TCP4 Direct"
        self.store.update_peer(peer_id, transport=label, endpoint=endpoint, connected_at=int(time.time()))
        session_id = str(uuid.uuid4())
        started_at = int(time.time())
        with self.store.connection:
            self.store.connection.execute(
                "INSERT INTO sessions(session_id, peer_id, family, endpoint, state, started_at) VALUES(?, ?, ?, ?, 'connected', ?)",
                (session_id, peer_id, 6 if family == socket.AF_INET6 else 4, endpoint, started_at),
            )
        try:
            while True:
                packet = await read_data(reader)
                packet_count += 1
                self._add_bytes(peer_id, "rx_bytes", len(packet))
                await self._handle_peer_packet(packet, writer, peer_id)
        except ProtocolError as exc:
            if packet_count == 0 and "connection closed while reading IP version" in str(exc):
                LOG.info(
                    "direct-adopt early-eof ts=%.6f peer_id=%s family=%s attempt=%s initiated=%s fd=%s local=%s remote=%s reason=%s",
                    time.time(),
                    peer_id,
                    "tcp6" if family == socket.AF_INET6 else "tcp4",
                    attempt,
                    "yes" if initiated else "no",
                    self._socket_fd(writer),
                    self._sockname_text(writer),
                    self._peername_text(writer),
                    exc,
                )
                return
            raise
        finally:
            if self.direct_writer is writer and self._direct_owner_token == token:
                self.direct_writer = None
                self.direct_peer_id = None
                self._direct_owner_token = None
                self._direct_owner_key = None
                self.store.update_peer(peer_id, transport="Disconnected")
            with self.store.connection:
                self.store.connection.execute(
                    "UPDATE sessions SET state='disconnected', ended_at=? WHERE session_id=?",
                    (int(time.time()), session_id),
                )
            LOG.info(
                "direct-adopt close ts=%.6f peer_id=%s family=%s attempt=%s initiated=%s fd=%s local=%s remote=%s packets=%s reason=stream-ended",
                time.time(),
                peer_id,
                "tcp6" if family == socket.AF_INET6 else "tcp4",
                attempt,
                "yes" if initiated else "no",
                self._socket_fd(writer),
                self._sockname_text(writer),
                self._peername_text(writer),
                packet_count,
            )
            writer.close()

    def _add_bytes(self, peer_id: str, column: str, amount: int) -> None:
        if column not in {"rx_bytes", "tx_bytes"}:
            raise ValueError("invalid counter")
        key = (peer_id, column)
        self._byte_counters[key] = self._byte_counters.get(key, 0) + amount

    def _flush_byte_counters(self) -> None:
        pending, self._byte_counters = self._byte_counters, {}
        if not pending:
            return
        with self.store.connection:
            for (peer_id, column), amount in pending.items():
                self.store.connection.execute(
                    f"UPDATE peers SET {column} = {column} + ?, updated_at = ? WHERE peer_id = ?",
                    (amount, int(time.time()), peer_id),
                )

    async def _statistics_loop(self) -> None:
        while True:
            await asyncio.sleep(1)
            self._flush_byte_counters()

    def _next_direct_attempt(self) -> int:
        self._direct_attempt_counter += 1
        return self._direct_attempt_counter

    def _clear_direct_connect_task(self, peer_id: str, task: asyncio.Task) -> None:
        if self._direct_connect_tasks.get(peer_id) is task:
            self._direct_connect_tasks.pop(peer_id, None)

    @staticmethod
    def _socket_fd(writer) -> int | None:
        sock = writer.get_extra_info("socket")
        if sock is None:
            return None
        try:
            return sock.fileno()
        except OSError:
            return None

    @staticmethod
    def _sockname_text(writer) -> str:
        try:
            return str(writer.get_extra_info("sockname"))
        except OSError:
            return "unknown"

    @staticmethod
    def _peername_text(writer) -> str:
        try:
            return str(writer.get_extra_info("peername"))
        except OSError:
            return "unknown"

    def _connection_key(self, writer) -> tuple[str, str]:
        local = self._sockname_text(writer)
        remote = self._peername_text(writer)
        return tuple(sorted((local, remote)))

    @staticmethod
    async def _write_data(writer, packet: bytes) -> None:
        writer.write(encode_data(packet))
        transport = writer.transport
        if transport is not None and transport.get_write_buffer_size() >= 256 * 1024:
            await writer.drain()

    async def _start_admin_listener(self) -> None:
        admin_socket = Path("/run/tcppeer/server-admin.sock")
        admin_socket.parent.mkdir(parents=True, exist_ok=True)
        admin_socket.unlink(missing_ok=True)

        listener = await asyncio.start_unix_server(
            self._handle_admin_client,
            path=admin_socket,
        )
        admin_socket.chmod(0o660)
        self._listeners.append(listener)
        LOG.info("Server admin socket listening on %s", admin_socket)

    async def _handle_admin_client(self, reader, writer) -> None:
        try:
            line = (
                await asyncio.wait_for(reader.readline(), timeout=5)
            ).decode("ascii").strip()

            parts = line.split()
            if len(parts) != 2 or parts[0] != "PING":
                writer.write(b"ERROR expected: PING <peer-id>\n")
                await writer.drain()
                return

            peer_id = parts[1]

            if any(ord(char) > 127 for char in peer_id):
                writer.write(b"ERROR invalid peer-id\n")
                await writer.drain()
                return

            if self.direct_writer is None or self.direct_peer_id != peer_id:
                writer.write(
                    f"ERROR peer {peer_id} is not directly connected\n".encode("ascii")
                )
                await writer.drain()
                return

            row = self.store.connection.execute(
                "SELECT overlay_ipv6 FROM peers WHERE peer_id = ?",
                (peer_id,),
            ).fetchone()

            if row is None or not row[0] or row[0] == "-":
                writer.write(
                    f"ERROR peer {peer_id} has no TCPeer IPv6 address\n".encode("ascii")
                )
                await writer.drain()
                return

            source = ipaddress.IPv6Address(self._active_server_ipv6)
            destination = ipaddress.IPv6Address(row[0])

            sequence = 0
            while True:
                sequence += 1
                identifier = (
                    ((os.getpid() & 0xffffffff) << 32)
                    | (uuid.uuid4().int & 0xffffffff)
                )
                sent_ns = time.monotonic_ns()

                future = asyncio.get_running_loop().create_future()
                self._pending_tpp_pings[identifier] = future

                packet = build_tpp(
                    source,
                    destination,
                    ECHO_REQUEST,
                    identifier,
                    sent_ns,
                )

                await self._write_data(self.direct_writer, packet)
                self._add_bytes(peer_id, "tx_bytes", len(packet))

                try:
                    await asyncio.wait_for(future, timeout=3.0)
                except asyncio.TimeoutError:
                    writer.write(
                        f"timeout from {destination}: seq={sequence}\n".encode("ascii")
                    )
                else:
                    elapsed = (time.monotonic_ns() - sent_ns) / 1_000_000
                    writer.write(
                        f"reply from {destination}: seq={sequence} "
                        f"time={elapsed:.1f} ms\n".encode("ascii")
                    )
                finally:
                    self._pending_tpp_pings.pop(identifier, None)

                await writer.drain()

                await asyncio.sleep(1.0)

        except (TimeoutError, UnicodeError, ConnectionError, asyncio.CancelledError):
            pass
        finally:
            writer.close()
            await writer.wait_closed()

    async def _tun_loop(self) -> None:
        while True:
            packet = await self._read_tun()
            if self.direct_writer is not None and self.direct_peer_id is not None:
                await self._write_data(self.direct_writer, packet)
                self._add_bytes(self.direct_peer_id, "tx_bytes", len(packet))

    async def _read_tun(self) -> bytes:
        if self.tun.fd is None:
            raise RuntimeError("TUN interface is closed")
        loop = asyncio.get_running_loop()
        while True:
            try:
                return os.read(self.tun.fd, 65535)
            except BlockingIOError:
                ready = loop.create_future()
                def mark_ready() -> None:
                    if not ready.done():
                        ready.set_result(None)
                loop.add_reader(self.tun.fd, mark_ready)
                try:
                    await ready
                finally:
                    loop.remove_reader(self.tun.fd)

    async def _handle_peer_packet(self, packet: bytes, writer, peer_id: str) -> None:
        tpp = parse_tpp(packet)
        if (
            tpp is not None
            and tpp.kind == ECHO_REPLY
            and tpp.destination == self._active_server_ipv6
        ):
            future = self._pending_tpp_pings.get(tpp.identifier)
            if future is not None and not future.done():
                future.set_result(tpp)
            return

        if tpp is not None and tpp.kind == ECHO_REQUEST and tpp.destination == self._active_server_ipv6:
            response = build_tpp_reply(packet)
            if response is not None:
                await self._write_data(writer, response)
                self._add_bytes(peer_id, "tx_bytes", len(response))
            return
        payload = extract_dhcp_payload(packet)
        if payload is not None:
            reply = self.dhcp.handle(payload)
            if reply is not None:
                response = build_dhcp_packet(
                    reply, self.config.server_ipv4,
                    ipaddress.ip_address("255.255.255.255"),
                )
                await self._write_data(writer, response)
                self._add_bytes(peer_id, "tx_bytes", len(response))
            return
        if is_router_solicitation(packet):
            response = self._ra_packet(ipv6_source(packet) or ALL_NODES)
            await self._write_data(writer, response)
            self._add_bytes(peer_id, "tx_bytes", len(response))
            return
        self.tun.write(packet)

    async def _configure_initial_ipv6(self) -> None:
        interface = discover_ipv6_upstream({self.config.tun_name})
        self._pd_interface = interface

        if interface is None:
            LOG.warning("No IPv6 upstream interface found; using NAPT66 fallback")
            self._use_ipv6_fallback()
            return

        delegated = await PrefixDelegationClient(interface).acquire()
        if delegated is None:
            LOG.info(
                "Upstream %s provided no DHCPv6-PD; using NAPT66 fallback",
                interface,
            )
            self._use_ipv6_fallback()
            return

        subnet = slaac_subnet(delegated.prefix)
        if subnet is None:
            LOG.warning(
                "Delegated prefix %s is narrower than /64; using NAPT66 fallback",
                delegated.prefix,
            )
            self._use_ipv6_fallback()
            return

        self._active_ipv6_prefix = subnet
        self._active_server_ipv6 = router_address(subnet)
        self._pd_active = True
        self.exit_node.nat66_enabled = False

        LOG.info(
            "Using delegated IPv6 prefix %s for TCPeer SLAAC; NAT66 disabled",
            subnet,
        )

    def _use_ipv6_fallback(self) -> None:
        self._active_ipv6_prefix = self.config.ipv6_prefix
        self._active_server_ipv6 = self.config.server_ipv6
        self._pd_active = False
        self.exit_node.nat66_enabled = self.config.nat66

    async def _pd_loop(self) -> None:
        while True:
            await asyncio.sleep(60)

            interface = discover_ipv6_upstream({self.tun.name})
            if interface is None:
                if self._pd_active:
                    LOG.warning("IPv6 upstream disappeared; enabling NAPT66 fallback")
                    await self._switch_to_ipv6_fallback()
                continue

            delegated = await PrefixDelegationClient(interface).acquire()
            subnet = slaac_subnet(delegated.prefix) if delegated else None

            if subnet is None:
                if self._pd_active:
                    LOG.warning(
                        "DHCPv6-PD is no longer available; enabling NAPT66 fallback"
                    )
                    await self._switch_to_ipv6_fallback()
                continue

            if self._pd_active and subnet == self._active_ipv6_prefix:
                continue

            LOG.info("Switching TCPeer SLAAC to delegated prefix %s", subnet)

            self._active_ipv6_prefix = subnet
            self._active_server_ipv6 = router_address(subnet)
            self._pd_active = True
            self._pd_interface = interface

            self.tun.configure(
                str(self.config.server_ipv4),
                self.config.ipv4_subnet.prefixlen,
                str(self._active_server_ipv6),
                self._active_ipv6_prefix.prefixlen,
            )

            self.exit_node.nat66_enabled = False
            self.exit_node.apply()

            if self.direct_writer is not None:
                await self._write_data(self.direct_writer, self._ra_packet())

    async def _switch_to_ipv6_fallback(self) -> None:
        self._use_ipv6_fallback()

        self.tun.configure(
            str(self.config.server_ipv4),
            self.config.ipv4_subnet.prefixlen,
            str(self._active_server_ipv6),
            self._active_ipv6_prefix.prefixlen,
        )

        self.exit_node.apply()

        if self.direct_writer is not None:
            await self._write_data(self.direct_writer, self._ra_packet())

    def _ra_packet(self, destination: ipaddress.IPv6Address = ALL_NODES) -> bytes:
        return build_router_advertisement(
            LINK_LOCAL_ROUTER,
            self._active_ipv6_prefix,
            self.config.router_lifetime_seconds,
            self.config.preferred_lifetime_seconds,
            self.config.valid_lifetime_seconds,
            self.dns,
            destination,
        )

    async def _ra_loop(self) -> None:
        while True:
            if self.direct_writer is not None and self.direct_peer_id is not None:
                packet = self._ra_packet()
                await self._write_data(self.direct_writer, packet)
                self._add_bytes(self.direct_peer_id, "tx_bytes", len(packet))
            await asyncio.sleep(self.config.ra_interval_seconds)


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Run the TCPeer stateful VPN server")
    parser.add_argument("--config", default="/etc/tcppeer/server.toml")
    return parser


def main() -> None:
    args = build_parser().parse_args()
    try:
        config = ServerConfig.from_file(args.config)
    except (OSError, ConfigurationError) as exc:
        raise SystemExit(f"Configuration error: {exc}") from exc
    logging.basicConfig(level=config.log_level, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
    try:
        asyncio.run(Server(config).run())
    except KeyboardInterrupt:
        LOG.info("Server stopped")


if __name__ == "__main__":
    main()
