# TCPeer Technical Specification

## 1. Scope and guarantees

TCPeer is a dual-stack layer-3 overlay using direct TCP connections. The first
release consists of a coordinator, a stateful Linux server, and an Android
`VpnService` client.

The coordinator is control-plane only. It authenticates, registers peers,
observes source TCP mappings, exchanges endpoints, sends synchronization
signals, and handles keepalives. It must reject binary DATA and must never
forward a VPN packet. Direct-connect failure has no relay fallback.

There is intentionally no VPN traffic confidentiality or integrity. The
Secret Key is protected by an HMAC-SHA256 challenge response and is never sent
over the network. All other control fields and VPN packets remain cleartext.

## 2. Repository structure

```text
docs/                         protocol and architecture
examples/                     safe example TOML files
packaging/systemd/            service units
src/tcppeer/                  Python package
  coordinator.py              control-plane TCP service
  server.py                   stateful Linux VPN node
  protocol.py                 ASCII control and binary DATA framing
  transport.py                address policy and direct TCP setup
  tun.py                      Linux TUN lifecycle
  dhcp.py                     DHCPv4 packet engine
  ra.py                       IPv6 Router Advertisement packet engine
  state.py                    SQLite state and leases
  config.py                   validated TOML models
  configurator.py             interactive installation
  cli.py                      read-only operational commands
tests/                        automated contract tests
android-app/                  Kotlin VpnService and Material 3 application
```

## 3. Components

### Coordinator

The coordinator listens on TCP4 and/or TCP6 as explicitly configured. Each
accepted stream records the source address and source TCP port returned by
`accept()`. Following AUTH, it registers peer capabilities and endpoints,
matches requested peers, and sends endpoint and `PUNCH-GO` blocks to both
sides. It accepts ASCII control blocks only. A non-ASCII block, invalid header,
or direct DATA magic closes the connection with an error.

### Server

The server owns one dual-stack layer-3 TUN interface and a SQLite database. It
tracks peers, sessions, byte counters, and IPv4 leases. It connects to the
coordinator over TCP, then establishes a direct TCP stream to a peer. Binary IP
frames flow only between that direct stream and the TUN interface.

The TUN engine recognizes IPv4 and IPv6 from the high nibble of the first
octet. The outer TCP family is independent of the inner packet family, so one
TCP6 stream may carry both IPv4 and IPv6 packets.

An exit node enables nftables software flow offloading by default for the TUN
and automatically discovered upstream interfaces. NAT44 and NAT66 remain in
the conntrack path. Unsupported kernels fall back to normal forwarding.

## 4. ASCII control protocol

Every control message is ASCII and has this grammar:

```text
TCPeer/1.0 COMMAND\r\n
Field-Name: value\r\n
...\r\n
\r\n
```

Field names use printable ASCII and are case-insensitive when parsed. Values
must not contain CR or LF. A block is limited to 16 KiB. Defined commands are
`AUTH`, `AUTH-CHALLENGE`, `AUTH-PROOF`, `AUTH-OK`, `AUTH-ERROR`, `REGISTER`, `PEER-INFO`, `ENDPOINT-INFO`,
`PUNCH-READY`, `PUNCH-GO`, `PING`, `PONG`, `KEEPALIVE`, `ERROR`, and
`DISCONNECT`.

AUTH carries `Network` and `Peer-ID`. The coordinator returns a random nonce;
AUTH-PROOF carries HMAC-SHA256 over the protocol, network, peer ID, and nonce.
REGISTER carries the peer's declared IPv4/IPv6 endpoints and capabilities. ENDPOINT-INFO reports
the address and TCP port observed by the coordinator. PUNCH-READY identifies a
target. When both peers are ready, PUNCH-GO gives the remote endpoint, family,
and a shared start time.

## 5. Direct DATA framing

Control and DATA never share a coordinator connection. After a direct TCP
connection is established, peers exchange an ASCII `PEER-INFO` block and then
switch that stream to binary frames:

```text
0               31 32      39 40      47 48                       79
+-----------------+----------+----------+---------------------------+
| "TCPD" magic    | version  | family   | payload length (uint32 BE)|
+-----------------+----------+----------+---------------------------+
| raw IPv4 or IPv6 packet ...                                     |
+------------------------------------------------------------------+
```

Version is `1`. Family is `4` or `6`. Maximum payload is 65,535 bytes. Payload
is never Base64 encoded, encrypted, compressed, or sent through the
coordinator. The family byte must agree with the IP version nibble.

## 6. TCP simultaneous-open and hole punching

Each peer creates a listener and an outbound connector bound to the same local
address and TCP port, using address reuse where the operating system permits.
The coordinator observes that peer's control connection mapping and exchanges
observed and declared candidates. At `PUNCH-GO`, both peers call `connect()`
while accepting inbound connections. The first authenticated direct stream
wins; duplicates close.

Simultaneous-open depends on kernel and middlebox behavior. TCP NAT mappings
may be endpoint-dependent, and some NATs reject unsolicited SYNs or rewrite the
source port. TCPeer reports direct failure rather than relaying.

## 7. IPv6-first policy, NAT66, and NAPT66

Usable IPv6 means a non-unspecified, non-loopback, non-multicast address that
is not link-local-only. If both peers advertise usable IPv6, candidates and
sockets are exclusively `AF_INET6`. Neither coordinator nor peer may attempt
`AF_INET` after TCP6 failure.

TCP4 is selected only if at least one peer has no usable IPv6. Inner overlay
IPv4 packets can still travel over TCP6.

With native IPv6 or prefix-preserving NAT66, the coordinator-observed address
may be reachable while the port normally remains stable. With NAPT66, the
observed address and port are both candidates and simultaneous-open is still
required. Endpoint-dependent filtering or port remapping can make direct
connectivity impossible; this is an expected hard failure.

## 8. DHCPv4 over a layer-3 TUN

The server implements a stateful DHCPv4 packet engine for DISCOVER, OFFER,
REQUEST, ACK, NAK, RELEASE, renewal, and expiration. Leases are keyed by client
identifier, falling back to hardware address, and are allocated transactionally
from the configured pool to prevent duplicates.

TCPeer does not open a UDP socket. DHCP messages, which are UDP/IP packets at
the guest protocol level, are parsed from and written to the TUN as raw IP
packets. This preserves the TCP-only outer transport rule. Android does not
receive a fictitious Ethernet broadcast; assigned parameters are conveyed in
control metadata and applied through `VpnService.Builder`.

## 9. IPv6 SLAAC and Router Advertisement

The server emits ICMPv6 Router Advertisements through TUN. Each RA includes a
Prefix Information Option with Autonomous and On-Link flags, router lifetime,
preferred lifetime, and valid lifetime. An RDNSS option is included when IPv6
DNS servers are configured. The server also answers Router Solicitations.
DHCPv6 is out of scope.

Because TUN is layer 3, these are raw IPv6/ICMPv6 packets rather than Ethernet
frames. Checksums include the IPv6 pseudo-header.

## 10. Persistent state

The default database is `/var/lib/tcppeer/server/state.db`. SQLite foreign keys
and WAL mode are enabled. Tables cover metadata, peers, sessions, counters, and
IPv4 leases. Lease allocation uses `BEGIN IMMEDIATE` and a unique address
constraint. Expired leases are removed before allocation. Runtime state is
recoverable after restart; live TCP streams are not.

## 11. Configuration and systemd

Coordinator configuration defaults to `/etc/tcppeer/coordinator.toml`; server
configuration defaults to `/etc/tcppeer/server.toml`. The interactive
configurator asks, validates, creates directories/files, installs exactly one
unit, runs `systemctl daemon-reload`, and offers enable/start. It never keeps a
process alive.

Both services use foreground Python processes, `network-online.target`,
`Restart=on-failure`, journald, and systemd hardening. There is no internal
daemonization, `nohup`, terminal multiplexer, or shell supervisor loop.
The Python package must be installed before configuration. The configurator
resolves the installed component executable and refuses to create a unit if it
is missing, rather than leaving systemd in a `203/EXEC` restart loop.
The server sends a control-plane TCP keepalive after 30 seconds without a
message. A dead coordinator connection therefore becomes a process failure,
allowing `Restart=on-failure` to establish a fresh control connection.

## 12. Test plan

Automated tests scan and exercise the implementation to establish that it does
not create UDP sockets or reference relay/QUIC/WebRTC; the coordinator rejects
DATA; control is ASCII; framing preserves binary IPv4 and IPv6; family policy
selects TCP6 without TCP4 fallback; DHCP allocation is unique and persistent;
RA options and checksums are valid; and service units restart after failure.

Network-namespace integration tests should additionally create two Linux
peers, route IPv4 and IPv6 through TUN, inspect opened sockets, kill/restart the
systemd process, and test native IPv6 plus representative NAT66/NAPT66 rules.
Real-world NAT results cannot be proven by unit tests.

## 13. Android client

The Android application uses `VpnService.Builder` to apply the negotiated
IPv4 lease, SLAAC IPv6 address, routes, DNS servers, and MTU. It does not claim
that Android receives Ethernet broadcasts. DHCPv4 requests and IPv6 Router
Solicitations are encoded as inner binary IP packets and exchanged with the
server before the TUN interface is established.

Coordinator and direct sockets are protected from VPN routing before they are
connected. Both sides initiate a TCP connection from the coordinated local
port at `PUNCH-GO`; TCP6 failure is final when TCP6 was selected. The foreground
service shows only `Disconnected`, `Connecting`, `TCP6 Direct`, `TCP4 Direct`,
or `No Direct Connection`. The Compose Material 3 interface uses Material You
dynamic color on Android 12 and newer and a custom Material 3 fallback on older
supported releases.
