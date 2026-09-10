"""TCPeer Linux client using the shared direct TCP mesh implementation."""

from __future__ import annotations

import argparse
import asyncio
import hashlib
import ipaddress
import logging
import socket
import subprocess
import sys
from pathlib import Path

if __package__ in {None, ""}:
    sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from tcppeer.address_negotiation import dhcp_discover, dhcp_request, parse_dhcp, parse_ra, router_solicitation, transaction_id
from tcppeer.config import ClientConfig, ConfigurationError
from tcppeer.dns import discover_upstream_dns
from tcppeer.protocol import ControlMessage, ProtocolError, read_data
from tcppeer.server import Server, discover_direct_ipv4, discover_direct_ipv6, public_address
from tcppeer.state import StateStore
from tcppeer.tpp import ECHO_REPLY, ECHO_REQUEST, build_reply as build_tpp_reply, parse_tpp
from tcppeer.tun import TunDevice

LOG = logging.getLogger("tcppeer.client")


class Client(Server):
    """A non-routing Linux peer; coordinator stays control-only and peers stay direct."""

    def __init__(self, config: ClientConfig):
        self.config = config
        self.store = StateStore(config.state_db)
        self.dns = discover_upstream_dns({config.tun_name})
        self.tun = TunDevice(config.tun_name, config.mtu)
        self.direct_writers = {}
        self._peer_send_queues = {}
        self._peer_send_tasks = {}
        self._tasks = set()
        self._listeners = []
        self._direct_bind_ipv4 = config.direct_ipv4 or discover_direct_ipv4({config.tun_name})
        self._direct_bind_ipv6 = config.direct_ipv6 or discover_direct_ipv6({config.tun_name})
        self._registered_ipv4 = public_address(self._direct_bind_ipv4)
        # A syntactically global IPv6 on the physical interface may still be
        # the inside address of NAPT66. Only an address observed by the
        # coordinator may be advertised as the public IPv6 endpoint.
        self._registered_ipv6 = None
        self._registered_port_ipv4 = config.direct_port if self._registered_ipv4 else None
        self._registered_port_ipv6 = None
        self._direct_candidates = {}
        self._direct_connect_tasks = {}
        self._direct_adoption_lock = asyncio.Lock()
        self._direct_owner_tokens = {}
        self._direct_owner_keys = {}
        self._direct_owner_committed = set()
        self._direct_attempt_counter = 0
        self._byte_counters = {}
        self._coordinator_writer = None
        self._registration_complete = asyncio.Event()
        self._pending_tpp_pings = {}
        self._active_server_ipv6 = ipaddress.IPv6Address("::")
        self._overlay_ipv4: ipaddress.IPv4Address | None = None
        self._overlay_ipv6: ipaddress.IPv6Address | None = None
        self._overlay_ipv4_prefix = 0
        self._overlay_ipv6_prefix = 0
        self._configured = asyncio.Event()
        self._upstream_routes = {
            socket.AF_INET: self._read_default_route(socket.AF_INET),
            socket.AF_INET6: self._read_default_route(socket.AF_INET6),
        }

    def _registration_role(self) -> str:
        return "Client"

    def _registration_overlays(self) -> tuple[str, str]:
        return str(self._overlay_ipv4 or ""), str(self._overlay_ipv6 or "")

    async def _query_observed_endpoint(
        self, family: socket.AddressFamily,
    ) -> tuple[str, int] | None:
        """Discover NAPT endpoints without colliding with the punch socket."""
        reserved = self._direct_candidates.pop(family, None)
        if reserved is not None:
            reserved.close()
        try:
            return await super()._query_observed_endpoint(family)
        finally:
            local_address = (
                self._direct_bind_ipv6 if family == socket.AF_INET6
                else self._direct_bind_ipv4
            ) or ("::" if family == socket.AF_INET6 else "0.0.0.0")
            candidate = socket.socket(family, socket.SOCK_STREAM, socket.IPPROTO_TCP)
            candidate.setblocking(False)
            candidate.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            if hasattr(socket, "SO_REUSEPORT"):
                candidate.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEPORT, 1)
            try:
                candidate.bind((local_address, self.config.direct_port))
            except OSError as exc:
                candidate.close()
                LOG.warning(
                    "Could not restore TCP%s hole-punch candidate after endpoint discovery: %s",
                    6 if family == socket.AF_INET6 else 4,
                    exc,
                )
            else:
                self._direct_candidates[family] = candidate

    async def run(self) -> None:
        self.tun.open()
        try:
            self._prepare_direct_candidates()
            control = asyncio.create_task(self._control_loop(), name="control")
            registration = asyncio.create_task(self._registration_complete.wait(), name="registration-wait")
            done, _ = await asyncio.wait({control, registration}, return_when=asyncio.FIRST_COMPLETED)
            if control in done:
                registration.cancel()
                await asyncio.gather(registration, return_exceptions=True)
                control.result()
                raise ConnectionError("coordinator control loop ended before endpoint registration")
            await self._start_direct_listeners()
            await self._start_admin_listener()
            self._tasks.update({
                control,
                asyncio.create_task(self._tun_loop(), name="tun"),
                asyncio.create_task(self._statistics_loop(), name="statistics"),
                asyncio.create_task(self._address_change_loop(), name="address-change"),
            })
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
            for writer in self.direct_writers.values():
                writer.close()
            for task in self._peer_send_tasks.values():
                task.cancel()
            await asyncio.gather(*(listener.wait_closed() for listener in self._listeners), return_exceptions=True)
            await asyncio.gather(*self._tasks, return_exceptions=True)
            await asyncio.gather(*self._peer_send_tasks.values(), return_exceptions=True)
            self.tun.close()
            self._flush_byte_counters()
            self.store.close()

    async def _address_change_loop(self) -> None:
        """Reconnect when a usable physical address appears or changes."""
        initial_ipv4 = self._direct_bind_ipv4
        initial_ipv6 = self._direct_bind_ipv6
        while True:
            await asyncio.sleep(5)
            current_ipv4 = self.config.direct_ipv4 or discover_direct_ipv4({self.config.tun_name})
            current_ipv6 = self.config.direct_ipv6 or discover_direct_ipv6({self.config.tun_name})
            if current_ipv4 != initial_ipv4 or current_ipv6 != initial_ipv6:
                LOG.warning(
                    "Physical addresses changed IPv4=%s->%s IPv6=%s->%s; reconnecting to re-register endpoints",
                    initial_ipv4 or "none", current_ipv4 or "none",
                    initial_ipv6 or "none", current_ipv6 or "none",
                )
                raise ConnectionError("physical direct addresses changed")

    async def _before_direct_data(self, reader, writer, peer_id: str) -> None:
        if peer_id != self.config.target_peer or self._configured.is_set():
            return
        xid = transaction_id()
        await self._write_data(writer, dhcp_discover(self.config.peer_id, xid))
        await self._write_data(writer, router_solicitation())
        offer = ack = slaac = None
        for _ in range(8):
            packet = await asyncio.wait_for(read_data(reader), timeout=8)
            if offer is None:
                offer = parse_dhcp(packet, xid, 2)
                if offer is not None:
                    await self._write_data(writer, dhcp_request(self.config.peer_id, offer))
            if ack is None:
                ack = parse_dhcp(packet, xid, 5)
            if slaac is None:
                interface_id = int.from_bytes(hashlib.sha256(self.config.peer_id.encode("ascii")).digest()[:8], "big")
                slaac = parse_ra(packet, interface_id)
            if ack is not None and slaac is not None:
                break
        if ack is None or slaac is None:
            raise ProtocolError("address negotiation did not provide both DHCPv4 and SLAAC")
        old_ipv4 = self._overlay_ipv4
        old_ipv6 = self._overlay_ipv6
        old_ipv4_prefix = self._overlay_ipv4_prefix
        old_ipv6_prefix = self._overlay_ipv6_prefix
        if old_ipv4 is not None and old_ipv4 != ack.address:
            self._remove_overlay_route(old_ipv4, old_ipv4_prefix)
            self.tun.remove_address(str(old_ipv4), old_ipv4_prefix)
        if old_ipv6 is not None and old_ipv6 != slaac.address:
            self._remove_overlay_route(old_ipv6, old_ipv6_prefix)
            self.tun.remove_address(str(old_ipv6), old_ipv6_prefix)
        self._overlay_ipv4, self._overlay_ipv6 = ack.address, slaac.address
        self._overlay_ipv4_prefix, self._overlay_ipv6_prefix = ack.prefix_length, slaac.prefix.prefixlen
        self._active_server_ipv6 = slaac.address
        self.tun.configure(str(ack.address), ack.prefix_length, str(slaac.address), slaac.prefix.prefixlen)
        self._configure_routes(ack.server, slaac.prefix, tuple(ack.dns) + tuple(slaac.dns))
        self._configured.set()
        if self._coordinator_writer is not None:
            self._coordinator_writer.write(ControlMessage("PEER-INFO", {
                "Action": "Overlay-Update", "Overlay-IPv4": str(ack.address), "Overlay-IPv6": str(slaac.address),
            }).encode())
            await self._coordinator_writer.drain()
        LOG.info("PeerNet addresses configured IPv4=%s/%s IPv6=%s/%s use_exit_node=%s", ack.address, ack.prefix_length, slaac.address, slaac.prefix.prefixlen, self.config.use_exit_node)

    async def _after_direct_data(self, peer_id: str) -> None:
        if peer_id != self.config.target_peer:
            return
        self._configured.clear()
        LOG.info(
            "Primary peer %s disconnected; the next direct connection will renegotiate DHCPv4 and SLAAC",
            peer_id,
        )
        writer = getattr(self, "_coordinator_writer", None)
        if writer is not None and not writer.is_closing():
            try:
                writer.write(ControlMessage("PUNCH-READY", {"Peer-ID": peer_id}).encode())
                await writer.drain()
                LOG.info("Requested a fresh direct connection to primary peer %s", peer_id)
            except (ConnectionError, OSError) as exc:
                LOG.warning("Could not request immediate reconnect to primary peer %s: %s", peer_id, exc)

    def _remove_overlay_route(
        self,
        address: ipaddress.IPv4Address | ipaddress.IPv6Address,
        prefix: int,
    ) -> None:
        network = ipaddress.ip_network(f"{address}/{prefix}", strict=False)
        command = ["ip"]
        if network.version == 6:
            command.append("-6")
        command += ["route", "delete", str(network), "dev", self.tun.name]
        try:
            subprocess.run(command, check=False, capture_output=True, text=True)
        except OSError:
            LOG.warning("Could not remove stale PeerNet route %s", network)

    def _configure_routes(self, ipv4_router, ipv6_prefix, dns: tuple[str, ...]) -> None:
        routes = [("ip", "route", "replace", str(ipaddress.ip_network(f"{self._overlay_ipv4}/{self._overlay_ipv4_prefix}", strict=False)), "dev", self.tun.name), ("ip", "-6", "route", "replace", str(ipv6_prefix), "dev", self.tun.name)]
        if self.config.use_exit_node:
            for family, _kind, _protocol, _name, endpoint in socket.getaddrinfo(
                self.config.coordinator_address, self.config.coordinator_port,
                type=socket.SOCK_STREAM,
            ):
                if family in self._upstream_routes:
                    self._prepare_remote_route(str(endpoint[0]), family)
            routes += [("ip", "route", "replace", "default", "via", str(ipv4_router), "dev", self.tun.name), ("ip", "-6", "route", "replace", "default", "dev", self.tun.name)]
        for command in routes:
            subprocess.run(command, check=True, capture_output=True, text=True)
        if self.config.use_exit_node and dns:
            try:
                subprocess.run(("resolvectl", "dns", self.tun.name, *dns), check=False, capture_output=True, text=True)
                subprocess.run(("resolvectl", "domain", self.tun.name, "~."), check=False, capture_output=True, text=True)
            except OSError:
                LOG.warning("resolvectl is unavailable; received DNS servers were not installed")

    @staticmethod
    def _read_default_route(family: socket.AddressFamily) -> tuple[str | None, str] | None:
        command = ("ip", "-6", "route", "show", "default") if family == socket.AF_INET6 else ("ip", "route", "show", "default")
        try:
            fields = subprocess.run(command, check=True, capture_output=True, text=True).stdout.splitlines()[0].split()
            gateway = fields[fields.index("via") + 1] if "via" in fields else None
            device = fields[fields.index("dev") + 1]
            return gateway, device
        except (OSError, subprocess.CalledProcessError, IndexError, ValueError):
            return None

    def _prepare_remote_route(self, address: str, family: socket.AddressFamily) -> None:
        if not self.config.use_exit_node:
            return
        upstream = self._upstream_routes.get(family)
        if upstream is None:
            raise RuntimeError(f"no upstream {'IPv6' if family == socket.AF_INET6 else 'IPv4'} route for outer TCP")
        gateway, device = upstream
        prefix = "128" if family == socket.AF_INET6 else "32"
        command = ["ip"]
        if family == socket.AF_INET6:
            command.append("-6")
        command += ["route", "replace", f"{address.split('%', 1)[0]}/{prefix}"]
        if gateway:
            command += ["via", gateway]
        command += ["dev", device]
        subprocess.run(command, check=True, capture_output=True, text=True)

    async def _handle_peer_packet(self, packet: bytes, writer, peer_id: str) -> None:
        tpp = parse_tpp(packet)
        if tpp is not None and tpp.kind == ECHO_REPLY and tpp.destination == self._overlay_ipv6:
            future = self._pending_tpp_pings.get(tpp.identifier)
            if future is not None and not future.done():
                future.set_result(tpp)
            return
        if tpp is not None and tpp.kind == ECHO_REQUEST and tpp.destination == self._overlay_ipv6:
            reply = build_tpp_reply(packet)
            if reply is not None:
                await self._write_data(writer, reply)
            return
        self.tun.write(packet)


def main() -> None:
    parser = argparse.ArgumentParser(description="Run the TCPeer Linux client")
    parser.add_argument("--config", default="/etc/tcppeer/client.toml")
    args = parser.parse_args()
    try:
        config = ClientConfig.from_file(args.config)
    except (OSError, ConfigurationError) as exc:
        raise SystemExit(f"Configuration error: {exc}") from exc
    logging.basicConfig(level=config.log_level, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
    try:
        asyncio.run(Client(config).run())
    except KeyboardInterrupt:
        LOG.info("Client stopped")


if __name__ == "__main__":
    main()
