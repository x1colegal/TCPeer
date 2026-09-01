# TCPeer

TCPeer is an experimental dual-stack Layer 3 peer-to-peer VPN built around direct TCP connections.

TCP is the outer transport used for VPN traffic. A lightweight coordinator provides authentication, peer discovery, endpoint registration, device synchronization, and coordinated TCP simultaneous-open.

The coordinator does **not** relay tunneled traffic.

Once a direct connection is established, IPv4 and IPv6 packets are transferred directly between peers as their original raw IP bytes.

**TCPeer adds 0 bytes of TCPeer-specific framing overhead to the data plane.**

> [!WARNING]
> TCPeer is not an encrypted VPN.
>
> Authentication proves possession of the configured Secret Key using HMAC-SHA256, but the direct TCP stream itself is cleartext.
>
> Applications should use their own encryption, such as HTTPS or SSH, when confidentiality is required.

---

## Features

TCPeer currently provides:

- Direct peer-to-peer TCP transport
- TCP over IPv4 and IPv6
- Coordinator-assisted peer discovery
- Separate IPv4 and IPv6 endpoint registration
- TCP simultaneous-open / TCP hole punching
- HMAC-SHA256 Secret Key authentication
- Raw IPv4 and IPv6 Layer 3 tunneling
- **0-byte TCPeer data-plane framing**
- Linux TUN interface
- Native Android `VpnService` client
- IPv4 DHCP support
- IPv6 SLAAC and Router Advertisements
- DHCPv6 Prefix Delegation support
- Automatic upstream DNS discovery
- RDNSS advertisement
- IPv4 and IPv6 forwarding
- NAT44
- NAT66
- nftables integration
- Optional nftables software flow offload
- Linux exit-node operation
- Persistent SQLite server state and coordinator device inventory
- Device/peer synchronization
- Automatic Android reconnection
- TPP / TCPPeerPing
- Coordinator-local administration
- Interactive Linux configuration
- systemd services

---

# Architecture

A normal TCPeer deployment consists of:

1. A coordinator
2. A Linux server / exit node
3. One or more Android or Linux clients

```text
                         CONTROL PLANE

                   +----------------------+
                   |     Coordinator      |
                   |                      |
                   | Authentication       |
                   | Peer discovery       |
                   | Endpoint discovery   |
                   | Connection sync      |
                   | Device state         |
                   +----------+-----------+
                              |
                    TCP control connections
                         /            \
                        /              \
                       /                \
          +-----------+----+       +----+-------------+
          | Android Client |       | Linux Server     |
          | VpnService     |       | / Exit Node      |
          +-----------+----+       +----+-------------+
                      \                 /
                       \               /
                        \             /
                    direct TCP4 / TCP6
                             |
                             |
                         DATA PLANE
                             |
                   raw IPv4 / IPv6
                             |
                   0 bytes of TCPeer
                       framing
```

The coordinator participates only in the control plane.

Actual VPN packets travel directly between peers.

There is no coordinator traffic relay.

---

# Direct transport

TCPeer uses **TCP** as its outer transport.

It does not use an outer:

- UDP tunnel
- QUIC tunnel
- HTTP/3 tunnel
- WebRTC tunnel
- WireGuard transport
- coordinator packet relay

A direct connection can use:

```text
TCP4
```

or:

```text
TCP6
```

The IP family of the outer TCP connection is independent of the IP family of the tunneled packet.

For example:

```text
Outer connection:

IPv6
  |
 TCP
  |
  +-- raw IPv4 packet
  +-- raw IPv6 packet
  +-- raw IPv4 packet
  +-- raw IPv6 packet
```

---

# RAW IP data plane

The current TCPeer data plane does **not** use TCPD or another TCPeer-specific per-packet frame.

There is:

```text
NO TCPD
NO DATA magic
NO TCPeer packet-length prefix
NO ASCII packet metadata
NO binary TCPeer DATA header
NO transport-header conversion
NO TCP/UDP/SCTP/DCCP header reconstruction
NO TCPeer checksum field
```

The direct TCP stream contains the original IP packets.

Conceptually:

```text
<IPv6 packet><IPv4 packet><IPv6 packet><IPv6 packet>...
```

If a packet obtained from the TUN starts with:

```text
60 00 00 00 00 28 06 40 ...
```

those bytes are written directly to the direct TCP connection.

TCPeer does not prepend a TCPD header or any other data-plane header.

Therefore:

```text
TCPeer-specific framing overhead = 0 bytes per IP packet
```

This does **not** mean the network itself has zero overhead.

The normal headers still exist:

```text
Outer IP
Outer TCP
Inner IP
Inner transport protocol
Application data
```

The zero-byte statement refers specifically to additional **TCPeer data-plane framing**.

---

# Packet boundaries over TCP

TCP is a byte stream.

It does not preserve application `write()` boundaries.

For example, a sender may perform:

```text
write(packetA)
write(packetB)
write(packetC)
```

but the receiver must not assume that three corresponding TCP `read()` calls will return exactly those three packets.

TCPeer therefore derives packet boundaries from information already contained in each raw IP packet.

No additional TCPeer framing bytes are required.

---

## IPv4 packet boundaries

The first nibble identifies IPv4:

```text
Version = 4
```

The IPv4 header contains the 16-bit:

```text
Total Length
```

field.

That value describes the complete IPv4 packet.

Conceptually:

```text
+----------------------+
| IPv4 header          |
|                      |
| Total Length = N     |
+----------------------+
| Remaining IPv4 data  |
|                      |
+----------------------+

Complete packet = N bytes
```

TCPeer can therefore determine where the current IPv4 packet ends and where the next packet begins without adding its own length field.

IPv4 options are naturally included because `Total Length` covers the complete IPv4 packet.

---

## IPv6 packet boundaries

IPv6 uses a fixed 40-byte base header.

The base header contains:

```text
Payload Length
```

For ordinary IPv6 packets, the complete packet length is therefore:

```text
40 + Payload Length
```

Conceptually:

```text
+----------------------+ 40 bytes
| IPv6 base header     |
|                      |
| Payload Length = N   |
+----------------------+
| IPv6 payload         | N bytes
+----------------------+

Complete packet = 40 + N bytes
```

The next byte after that packet belongs to the next raw IP packet in the TCP stream.

---

# Inner protocols

The raw data plane is not limited to TCP payloads.

TCPeer carries complete Layer 3 packets.

Examples include:

```text
IPv4 / TCP
IPv4 / UDP
IPv4 / ICMP
IPv4 / SCTP
IPv4 / DCCP

IPv6 / TCP
IPv6 / UDP
IPv6 / ICMPv6
IPv6 / SCTP
IPv6 / DCCP
IPv6 / TPP
```

TCPeer does not need to convert the inner transport header into separate metadata.

For example, an IPv6/TCP packet remains:

```text
+-------------------+
| IPv6 header       |
+-------------------+
| TCP header        |
+-------------------+
| TCP payload       |
+-------------------+
```

The complete byte sequence is transferred as the inner raw IPv6 packet.

The same principle applies to UDP, ICMP, ICMPv6, SCTP, DCCP, TPP, DHCP, DNS, and other protocols carried inside IPv4 or IPv6.

---

# Packet path

## Android -> Linux

```text
Android application
        |
        v
Android IP stack
        |
        v
VpnService TUN
        |
        v
raw IP packet
        |
        v
direct TCP connection
        |
        v
raw IP packet
        |
        v
Linux tcppeer0
        |
        +------> routed network
        |
        +------> exit-node forwarding
        |
        +------> Internet
```

No TCPeer DATA/TCPD header is inserted between the two TUN endpoints.

---

## Linux -> Android

```text
Linux tcppeer0
        |
        v
raw IP packet
        |
        v
direct TCP connection
        |
        v
raw IP packet
        |
        v
Android VpnService TUN
        |
        v
Android IP stack
```

---

# Control plane vs data plane

TCPeer intentionally separates the control plane from the data plane.

## Control plane

The coordinator is responsible for information such as:

```text
Authentication
Peer registration
Peer discovery
Endpoint discovery
Device synchronization
Direct-connection coordination
Keepalive
Administration
```

Control-plane messages have their own protocol.

They are not raw IP packets.

## Data plane

After direct connectivity is established, the peer-to-peer data stream carries:

```text
raw IP packets
```

There is no TCPD layer between outer TCP and the inner IP packets.

---

# Coordinator

The coordinator handles TCPeer's control plane.

Its responsibilities include:

- Network authentication
- Peer authentication
- Peer registration
- Public endpoint observation
- IPv4 candidate registration
- IPv6 candidate registration
- Peer discovery
- Direct-connection coordination
- Device information synchronization
- Online/offline state
- Coordinator-local administration

The coordinator does **not** become the VPN data path.

If peers cannot establish the required direct connection, the coordinator does not become a packet relay.

---

# Authentication

Peers belonging to the same TCPeer network share a Secret Key.

The Secret Key itself is not transmitted as the authentication proof.

TCPeer uses HMAC-SHA256 challenge/response authentication.

A fresh coordinator nonce is used in authentication.

This proves possession of the shared Secret Key.

It does **not** encrypt the direct TCP stream.

---

# TCP simultaneous-open

TCPeer can coordinate TCP simultaneous-open for direct connectivity through compatible NAT implementations.

Conceptually:

```text
Peer A                           Peer B
  |                                |
  | endpoint registration          |
  |------------------------------->|
  |                                |
  |        Coordinator             |
  |             |                  |
  |        synchronization         |
  |             |                  |
  |<------------+----------------->|
  |                                |
  |---- TCP SYN ----------->        |
  |        <----------- TCP SYN ----|
  |                                |
  |====== direct TCP stream =======|
```

Actual success depends on:

- NAT behavior
- Firewall behavior
- Source-port preservation
- Endpoint mapping behavior
- CGNAT behavior
- TCP simultaneous-open support
- Reachability between both peers

Restrictive or endpoint-dependent NAT implementations can prevent a direct connection.

---

# IPv4 and IPv6 direct connectivity

TCPeer tracks IPv4 and IPv6 direct endpoints independently.

A direct connection may therefore be:

```text
TCP4
```

or:

```text
TCP6
```

When a usable IPv6 path exists, TCPeer can use a direct TCP6 connection.

TCP4 remains available for networks where direct IPv6 connectivity is unavailable.

The inner VPN remains dual-stack regardless of which outer family is selected.

For example:

```text
TCP6 outer connection
        |
        +--- IPv4 inner traffic
        |
        +--- IPv6 inner traffic
```

is valid.

Likewise:

```text
TCP4 outer connection
        |
        +--- IPv4 inner traffic
        |
        +--- IPv6 inner traffic
```

is valid.

---

# Linux server

The Linux component provides the server side of the TCPeer Layer 3 tunnel.

It normally creates:

```text
tcppeer0
```

as a TUN interface.

The server can also operate as an Internet exit node.

Typical requirements:

- Linux
- Python 3.11+
- `/dev/net/tun`
- `ip`
- `nft`
- `sysctl`
- systemd for the provided service setup
- sufficient network privileges

---

# Linux client

Linux can also join a PeerNet as a non-routing client. It reuses the same TCP
endpoint discovery, TCP4/TCP6 simultaneous-open, deterministic direct-socket
arbitration, raw-IP stream, mesh, and TPP implementation as the Exit Node.

The selected `target_peer` must provide TCPeer DHCPv4 and SLAAC so the client
can receive its overlay addresses. With `routing.use_exit_node = false`, only
the learned PeerNet IPv4 and IPv6 prefixes use `tcppeer0`; normal Internet and
DNS stay on the Linux host's upstream network. With it enabled, IPv4/IPv6
default routes and the received DNS servers use the selected Exit Node.

The Linux client remains direct-only: the coordinator authenticates and
coordinates TCP hole punching but never relays data, and an Exit Node does not
relay packets between two mesh peers that should have their own direct socket.

---

# Android client

The Android application is a native TCPeer client built around Android `VpnService`.

The Android client handles:

- Coordinator connection
- Authentication
- Peer discovery
- Direct TCP4/TCP6 establishment
- TUN/VPN configuration
- IPv4 routing
- IPv6 routing
- Raw IP packet transfer
- Automatic reconnect
- Network-change handling
- Device information
- Traffic statistics
- TPP ping

The VPN socket used for TCPeer itself must remain outside the VPN route to prevent recursive tunneling.

Android's `VpnService.protect()` is used for this purpose.

---

# Android routing

TCPeer can install IPv4 and IPv6 routes through the Android VPN.

This allows the Linux peer to operate as an exit node.

Even when the Android option `Use Exit Node` is disabled, the Linux peer is
still required for address negotiation and routed TCPeer connectivity.

Disabling `Use Exit Node` only prevents normal internet traffic and exit-node
DNS from replacing the phone's own upstream connection. It does not remove the
need for a TCPeer server / exit node peer to assign IPv4 and IPv6 addresses.

Conceptually:

```text
Android application
       |
       v
default VPN route
       |
       v
TCPeer VpnService
       |
       v
protected direct TCP socket
       |
       v
physical Android network
       |
       v
Linux TCPeer server
       |
       v
Internet
```

The direct TCP socket itself is protected from the VPN so that TCPeer does not tunnel its own transport through itself.

---

# IPv4 addressing

TCPeer can provide IPv4 addressing to clients.

DHCPv4 traffic is transported **inside** the VPN as normal raw IPv4/UDP packets.

There is no special outer UDP DHCP tunnel.

Conceptually:

```text
IPv4
  |
 UDP
  |
DHCP
```

is simply another raw IPv4 packet inside the TCPeer data plane.

IPv4 leases can be persisted in SQLite.

---

# IPv6 addressing

TCPeer supports IPv6 configuration using:

- Router Solicitation
- Router Advertisement
- SLAAC
- RDNSS

ICMPv6 packets remain ordinary raw IPv6 packets.

For example:

```text
IPv6
  |
ICMPv6
  |
Router Solicitation
```

and:

```text
IPv6
  |
ICMPv6
  |
Router Advertisement
```

travel through the same raw IP data path as any other IPv6 packet.

---

# DHCPv6 Prefix Delegation

The Linux TCPeer server can obtain an IPv6 prefix from its upstream network using DHCPv6 Prefix Delegation.

A delegated prefix can then be used for TCPeer's IPv6 network.

This allows TCPeer to provide routed IPv6 connectivity to VPN clients using upstream-delegated address space.

When a routable delegated prefix is available, IPv6 can operate without NAT66.

When the configured topology requires translation, NAT66 can be enabled instead.

The exact behavior depends on the server configuration and upstream network.

---

# Exit node

The Linux server can operate as a TCPeer exit node.

Example configuration:

```toml
[exit_node]
enabled = true
nat44 = true
nat66 = true
software_flow_offload = true
```

When enabled, TCPeer can configure:

- IPv4 forwarding
- IPv6 forwarding
- forwarding from `tcppeer0`
- NAT44
- NAT66
- upstream-interface detection
- nftables rules
- optional software flow offload

If clients use directly routed IPv6 space, NAT66 can be disabled.

---

# NAT44 and NAT66

TCPeer uses nftables for forwarding and NAT.

TCPeer-managed tables may include:

```text
tcppeer_forward
tcppeer_nat44
tcppeer_nat66
```

NAT rules apply to traffic arriving through the TCPeer TUN according to the configured exit-node behavior.

This also allows routed traffic behind a TCPeer peer to be handled where supported by the deployment.

---

# Software flow offload

TCPeer can optionally configure nftables software flow offloading.

Example:

```toml
[exit_node]
software_flow_offload = true
```

When supported by the kernel and current networking topology, eligible forwarded flows can enter an nftables flowtable.

If software flow offload cannot be used, normal forwarding remains available.

---

# DNS

TCPeer can discover DNS servers from the Linux server's active upstream network.

IPv6 Router Advertisements can advertise DNS servers through RDNSS.

An empty configured DNS list can be used when automatic discovery is desired.

Example:

```toml
[ipv6]
dns = []
```

DNS itself receives no special TCPeer data framing.

A DNS packet remains an ordinary IPv4 or IPv6 packet in the tunnel.

---

# TPP / TCPPeerPing

TCPPeerPing, abbreviated **TPP**, is TCPeer's latency protocol.

TPP uses IPv6 Next Header:

```text
99
```

and the protocol magic:

```text
TPP1
```

TPP requests and replies contain timing information used to calculate round-trip latency.

A TPP packet is an ordinary inner IPv6 packet:

```text
+----------------------+
| IPv6 header          |
| Next Header = 99     |
+----------------------+
| TPP                  |
| Magic = TPP1         |
+----------------------+
```

Because the TCPeer data plane carries raw IPv6 packets, no special TCPD handling is required for TPP.

The Android application can provide continuous TPP latency measurements for connected peers.

---

# Device information

TCPeer synchronizes information about known peers.

The Android interface can expose information such as:

- Peer ID
- Online/offline state
- Role
- Platform
- Direct transport
- TCP4/TCP6 state
- Public IPv4
- Public IPv6
- TCPeer IPv4
- TCPeer IPv6
- Direct endpoint
- Traffic counters
- Last-seen information

---

# Persistent state

The Linux server stores runtime information in SQLite.

The default state database is:

```text
/var/lib/tcppeer/server/state.db
```

Persistent information can include:

- Peers
- Sessions
- Byte counters
- DHCP leases
- Address information
- Transport information

Live TCP connections naturally do not survive a process restart.

After restart, TCPeer reconnects to the coordinator and performs discovery/direct-connection establishment again.

The coordinator separately persists its known-device directory in:

```text
/var/lib/tcppeer/coordinator/state.db
```

Peer ID, role, platform, transport, last public IPv4/IPv6 candidates, overlay
addresses, endpoint, and last-seen time survive a coordinator restart. Loaded
entries start offline and become online only after authentication and
registration complete again.

---

# Coordinator administration

The coordinator provides a local administrative Unix socket.

Typical path:

```text
/run/tcppeer/coordinator-admin.sock
```

Use the installed `tcppeer-devices` command instead of writing to SQLite:

```bash
sudo tcppeer-devices list
sudo tcppeer-devices remove PHONE_PEER_ID
sudo tcppeer-devices remove PHONE_PEER_ID --network home --yes
```

Removal asks for confirmation unless `--yes` is provided. If the Peer-ID is
present in more than one PeerNet, `--network` resolves the ambiguity. Removing
an online device first detaches its active coordinator session, then deletes
its persistent record. It does not delete or relay VPN packets.

The underlying local protocol also accepts:

```text
DELETE NETWORK PEER_ID
```

This administrative interface belongs to the control plane.

It is not used to carry tunneled packets.

---

# Default ports

Typical defaults are:

| Purpose | Protocol | Port |
|---|---|---:|
| Coordinator | TCP | 7443 |
| Direct peer connection | TCP | 7444 |

Both are configurable.

TCPeer does not require an outer UDP data-plane port.

---

# Installation

Clone or extract the TCPeer source tree and enter the repository:

```bash
cd TCPeer
```

Install the Python package:

```bash
sudo python3 -m pip install . --break-system-packages
```

To force replacement of an existing installation:

```bash
sudo python3 -m pip install . \
  --break-system-packages \
  --force-reinstall
```

---

# `tcppeer.egg-info` permission problem

If installation fails with an error similar to:

```text
error: Cannot update time stamp of directory 'src/tcppeer.egg-info'
```

remove the generated metadata and reinstall:

```bash
sudo rm -rf src/tcppeer.egg-info

sudo python3 -m pip install . \
  --break-system-packages \
  --force-reinstall
```

---

# Interactive configuration

TCPeer includes an interactive Linux configurator.

Run:

```bash
sudo python3 configure.py
```

Typical configuration files include:

```text
/etc/tcppeer/coordinator.toml
/etc/tcppeer/server.toml
/etc/tcppeer/client.toml
```

The configurator can also install/configure the corresponding systemd services.

---

# Coordinator service

Restart:

```bash
sudo systemctl restart tcppeer-coordinator
```

Status:

```bash
sudo systemctl status tcppeer-coordinator --no-pager -l
```

Follow logs:

```bash
sudo journalctl -f -u tcppeer-coordinator
```

---

# Server service

Restart:

```bash
sudo systemctl restart tcppeer-server
```

Status:

```bash
sudo systemctl status tcppeer-server --no-pager -l
```

Follow logs:

```bash
sudo journalctl -f -u tcppeer-server
```

Inspect recent logs:

```bash
sudo journalctl -u tcppeer-server -n 200 --no-pager
```

---

# Linux client service

```bash
sudo systemctl status tcppeer-client --no-pager -l
sudo journalctl -f -u tcppeer-client
```

`configure.py` offers **Coordinator**, **Exit Node / Server**, and **Client**.
It installs the matching configuration and unit. Installing the Python project
also installs `tcppeer-devices` and `tcppeer-client`.

---

# Inspecting the Linux tunnel

Show the TCPeer TUN interface:

```bash
ip address show tcppeer0
```

IPv4 routes:

```bash
ip -4 route
```

IPv6 routes:

```bash
ip -6 route
```

nftables:

```bash
sudo nft list ruleset
```

Forwarding:

```bash
sysctl net.ipv4.ip_forward
sysctl net.ipv6.conf.all.forwarding
```

---

# Building the Android application

TCPeer's Android application is built with Gradle.

Requirements include:

- JDK 17
- Android SDK
- Gradle wrapper from the repository

Build the debug APK:

```bash
./gradlew assembleDebug
```

A successful build ends with:

```text
BUILD SUCCESSFUL
```

The APK is generated at:

```text
android-app/build/outputs/apk/debug/android-app-debug.apk
```

Run Android unit tests and build:

```bash
./gradlew \
  :android-app:testDebugUnitTest \
  :android-app:assembleDebug
```

---

# Installing the Android APK

Using ADB:

```bash
adb install -r \
  android-app/build/outputs/apk/debug/android-app-debug.apk
```

Or copy the APK to the Android device and install it there.

---

# Android configuration

Typical Android configuration contains:

```text
Coordinator Address
Coordinator Port
Network
Secret Key
Peer ID
Target Peer ID
Direct Port
MTU
```

The network name and Secret Key must match the coordinator configuration.

Each Peer ID should uniquely identify its peer.

The target peer identifies the Linux server / exit node used by the Android client.

This remains necessary even when `Use Exit Node` is disabled, because the
Android client still needs that peer to negotiate and receive its VPN IPv4 and
IPv6 addresses.

---

# Linux CLI

The TCPeer CLI can inspect server state.

Examples:

```bash
tcppeer --config /etc/tcppeer/server.toml status
```

```bash
tcppeer --config /etc/tcppeer/server.toml peers
```

```bash
tcppeer --config /etc/tcppeer/server.toml leases
```

```bash
tcppeer --config /etc/tcppeer/server.toml sessions
```

```bash
tcppeer --config /etc/tcppeer/server.toml addresses
```

```bash
tcppeer --config /etc/tcppeer/server.toml transport
```

```bash
tcppeer --config /etc/tcppeer/server.toml stats
```

On a Linux Client, `tcppeer` automatically uses `/etc/tcppeer/client.toml`
when no server configuration exists:

```bash
tcppeer status
tcppeer peers
tcppeer ping PEER_ID
```

An explicit Client configuration is also accepted:

```bash
tcppeer --config /etc/tcppeer/client.toml status
```

---

# Configuration overview

## Coordinator

Common coordinator configuration areas include:

| Section | Setting | Purpose |
|---|---|---|
| `listen` | `ipv4` | IPv4 listen address |
| `listen` | `ipv6` | IPv6 listen address |
| `listen` | `port` | Coordinator TCP port |
| `auth.networks` | network keys | TCPeer network Secret Keys |
| `runtime` | `log_level` | Logging |
| `runtime` | `max_message_size` | Control message limit |
| `runtime` | `keepalive_seconds` | Coordinator keepalive |
| `paths` | `state_db` | Persistent known-device database |

Typical coordinator port:

```text
7443/TCP
```

---

## Linux server

Common server configuration areas include:

| Section | Purpose |
|---|---|
| `coordinator` | Coordinator endpoint |
| `identity` | Network, Peer ID and Secret Key |
| `direct` | Direct TCP settings and target peer |
| `interface` | TUN interface and MTU |
| `exit_node` | Forwarding, NAT and flow offload |
| `ipv4` | IPv4 addressing and DHCP |
| `ipv6` | IPv6 addressing, SLAAC/RA and DNS |
| `paths` | Persistent state |
| `runtime` | Runtime/logging behavior |

Typical direct port:

```text
7444/TCP
```

## Linux client

| Section | Purpose |
|---|---|
| `coordinator` | Coordinator DNS/IP and TCP port |
| `identity` | PeerNet, Peer-ID, and Secret Key |
| `direct` | TCP candidates, port, and address-assignment peer |
| `interface` | TUN name and MTU |
| `routing` | `use_exit_node` Internet/DNS policy |
| `paths` | Local SQLite runtime state |
| `runtime` | Logging |

---

# RAW IP implementation

The sender and receiver must agree on the RAW IP stream format.

There is intentionally no TCPeer-specific data header.

---

## Sender

For each complete packet obtained from the TUN:

```text
1. Obtain the complete IP packet.
2. Verify that it is IPv4 or IPv6 as required.
3. Write the original packet bytes to the direct TCP stream.
4. Do not prepend a TCPeer header.
```

Conceptually:

```text
write(packet)
```

Not:

```text
write(length)
write(packet)
```

Not:

```text
write("TCPD")
write(metadata)
write(packet)
```

Not:

```text
write(DATA_MAGIC)
write(DATA_VERSION)
write(length)
write(packet)
```

---

## Receiver

The receiver parses the TCP byte stream using the inner IP packet's own length information.

Conceptually:

```text
read first bytes
        |
        v
determine IP version
        |
        +------ IPv4 ------> Total Length
        |
        +------ IPv6 ------> Payload Length + 40
        |
        v
read exactly remaining bytes
        |
        v
complete raw IP packet
        |
        v
write to TUN
```

---

# Important TCP stream behavior

The following assumption is invalid:

```text
one TCP write == one TCP read
```

TCP does not provide message boundaries.

For example:

```text
Sender:

write(IP packet A)
write(IP packet B)
write(IP packet C)
```

may be received internally as:

```text
read():
  end of A + beginning of B

read():
  rest of B + all of C
```

or any other byte-stream segmentation.

TCPeer must therefore reconstruct inner packet boundaries independently of TCP segment/read boundaries.

The IPv4 and IPv6 length fields provide enough information to do this without adding a TCPeer per-packet header.

---

# TCP segments are not TCPeer packets

The outer TCP implementation may:

- split one inner IP packet across several TCP segments
- combine bytes from multiple inner IP packets into one TCP segment
- retransmit data
- reorder network segments internally before exposing the ordered byte stream
- acknowledge data
- change TCP segmentation according to MSS/offload behavior

None of that changes the TCPeer RAW IP format.

TCPeer sees the resulting ordered TCP byte stream.

It reconstructs inner IP packets from that stream.

---

# No transport-header stripping

TCPeer does not strip the inner transport header.

For example, this inner packet:

```text
IPv6
TCP
HTTP payload
```

is transported as:

```text
[IPv6 header][TCP header][HTTP payload]
```

not:

```text
TCPD
Protocol=TCP
Source Port=...
Sequence=...
...
[HTTP payload]
```

Likewise:

```text
IPv6
ICMPv6
Router Advertisement
```

is transported as:

```text
[IPv6 header][ICMPv6 header][RA data]
```

The entire inner IP packet remains intact.

---

# Checksums

Because TCPeer transports the original inner packet rather than converting its headers into TCPeer metadata, TCPeer does not normally need to reconstruct inner TCP/UDP headers merely for transport.

The original inner packet includes its original protocol headers and checksum fields.

Normal kernel/TUN/network-stack behavior still applies.

---

# MTU

TCPeer is a Layer 3 VPN and the configured TUN MTU should account for the characteristics of the outer path.

TCPeer itself adds:

```text
0 bytes
```

of per-packet data framing.

However, the outer connection still uses:

```text
Outer IPv4 or IPv6
TCP
```

and therefore consumes ordinary network header space.

The configured MTU should be appropriate for the deployment.

---

# Performance

The RAW IP data path avoids TCPeer-specific per-packet framing work.

For each normal tunneled packet, TCPeer does not need to:

```text
serialize TCPD
parse TCPD
convert IP metadata to ASCII
convert transport metadata to ASCII
reconstruct inner TCP headers
reconstruct inner UDP headers
reconstruct inner SCTP headers
reconstruct inner DCCP headers
add a TCPeer packet-length field
add a TCPeer DATA magic
```

The intended hot path is essentially:

```text
TUN
 |
 v
raw packet
 |
 v
TCP stream
 |
 v
raw packet
 |
 v
TUN
```

The actual throughput still depends on factors including:

- CPU performance
- Android `VpnService`
- TUN performance
- TCP congestion control
- TCP receive/send buffers
- outer network RTT
- packet sizes
- NAT/firewall behavior
- Wi-Fi or mobile-network performance
- kernel networking behavior

---

# Troubleshooting

## `Connection lost. Retrying`

Inspect the Linux side:

```bash
sudo journalctl -f -u tcppeer-server
```

Inspect the Android process:

```bash
logcat --pid=$(pidof com.tcppeer.android)
```

Both peers must run compatible TCPeer versions.

In particular, a client using an obsolete TCPD/data-frame implementation is not compatible with a server expecting the current RAW IP data plane.

---

# Old TCPD errors

Errors such as:

```text
invalid TCPD magic
```

or:

```text
Unsupported TCPD transport protocol
```

indicate code from the previous TCPD-based implementation.

The current RAW IP data plane should not require TCPD parsing for direct tunneled packets.

Make sure both the Python server package and Android APK were rebuilt/reinstalled from the current source.

---

# Reinstalling the server after source changes

From the repository root:

```bash
sudo rm -rf src/tcppeer.egg-info
```

Then:

```bash
sudo python3 -m pip install . \
  --break-system-packages \
  --force-reinstall
```

Restart:

```bash
sudo systemctl restart tcppeer-server
```

Follow logs:

```bash
sudo journalctl -f -u tcppeer-server
```

---

# Rebuilding Android after source changes

```bash
./gradlew assembleDebug
```

APK:

```text
android-app/build/outputs/apk/debug/android-app-debug.apk
```

After installing the new APK, ensure the running Android application is actually the newly built version before debugging protocol incompatibilities.

---

# Direct connection debugging

Check server logs:

```bash
sudo journalctl -u tcppeer-server -n 200 --no-pager
```

Look for information about:

- Coordinator connection
- Endpoint registration
- Public IPv4
- Public IPv6
- Direct connection attempts
- TCP4
- TCP6
- Target Peer ID
- Connection establishment
- Connection closure

Also verify the relevant TCP direct port is reachable according to the network topology.

---

# IPv6 debugging

Inspect addresses:

```bash
ip -6 address
```

Inspect routes:

```bash
ip -6 route
```

Inspect TCPeer:

```bash
ip -6 address show tcppeer0
```

Inspect server logs:

```bash
sudo journalctl -u tcppeer-server -n 200 --no-pager
```

---

# IPv4 debugging

Inspect addresses:

```bash
ip -4 address
```

Routes:

```bash
ip -4 route
```

TCPeer:

```bash
ip -4 address show tcppeer0
```

---

# Exit-node debugging

Check forwarding:

```bash
sysctl net.ipv4.ip_forward
```

```bash
sysctl net.ipv6.conf.all.forwarding
```

Inspect nftables:

```bash
sudo nft list ruleset
```

Inspect default routes:

```bash
ip -4 route show default
```

```bash
ip -6 route show default
```

Inspect TCPeer logs:

```bash
sudo journalctl -u tcppeer-server -n 200 --no-pager
```

---

# Android debugging

Clear logcat:

```bash
logcat -c
```

Capture only the TCPeer process:

```bash
logcat --pid=$(pidof com.tcppeer.android) -v threadtime
```

Filter common TCPeer messages:

```bash
logcat -v threadtime | grep -iE 'TCPeer|TCPeerVpnService|ProtocolException'
```

Useful messages include:

```text
Direct IPV4 active connection established
Direct IPV6 active connection established
Connection failed
Connection lost
Retrying
```

---

# Development

Create a Python virtual environment:

```bash
python3 -m venv .venv
```

Activate it:

```bash
. .venv/bin/activate
```

Install the project for development:

```bash
python -m pip install -e '.[test]'
```

Run Python tests:

```bash
pytest
```

Build Android:

```bash
./gradlew assembleDebug
```

Run Android unit tests:

```bash
./gradlew :android-app:testDebugUnitTest
```

---

# Project layout

A typical TCPeer source tree contains:

```text
TCPeer/
├── android-app/
│   └── src/main/java/com/tcppeer/android/
│       ├── protocol/
│       ├── ui/
│       └── vpn/
│
├── src/
│   └── tcppeer/
│       ├── auth.py
│       ├── cli.py
│       ├── config.py
│       ├── configurator.py
│       ├── client.py
│       ├── coordinator.py
│       ├── coordinator_state.py
│       ├── devices_cli.py
│       ├── address_negotiation.py
│       ├── dhcp.py
│       ├── dns.py
│       ├── exit_node.py
│       ├── packet.py
│       ├── pd.py
│       ├── protocol.py
│       ├── ra.py
│       ├── server.py
│       ├── state.py
│       ├── tpp.py
│       ├── transport.py
│       └── tun.py
│
├── build.gradle.kts
├── settings.gradle.kts
├── pyproject.toml
└── README.md
```

---

# Security model

TCPeer currently provides authentication but not data-plane encryption.

## Provided

```text
Shared Secret Key
HMAC-SHA256 authentication
Fresh authentication challenge/nonce
Direct peer-to-peer transport
```

## Not provided

```text
TLS on the direct tunnel
Data-plane encryption
Authenticated encryption of tunneled packets
Traffic confidentiality from observers of the direct connection
```

Applications requiring confidentiality should use end-to-end encrypted protocols.

Examples:

```text
HTTPS
SSH
TLS-enabled applications
end-to-end encrypted application protocols
```

---

# Protocol summary

## Control

```text
Peer
 |
 | TCP
 v
Coordinator
 |
 +-- authentication
 +-- peer discovery
 +-- endpoint discovery
 +-- connection coordination
 +-- device state
```

## Data

```text
Android peer
     |
     | direct TCP4 or TCP6
     |
     v
Linux peer
```

Contents of the direct data stream:

```text
<raw IPv4 packet>
<raw IPv6 packet>
<raw IPv4 packet>
<raw IPv6 packet>
...
```

TCPeer-specific per-packet data framing:

```text
0 bytes
```

---

# Design principle

The current TCPeer data-plane design can be summarized as:

```text
The IP packet is already self-describing enough
to determine its normal packet length.

Do not wrap it in another TCPeer packet format.
```

IPv4 already provides:

```text
Total Length
```

IPv6 already provides:

```text
Payload Length
```

Therefore the direct TCP byte stream can transport consecutive raw IP packets without a TCPD or DATA wrapper.

---

# Summary

TCPeer is a direct TCP-based dual-stack Layer 3 VPN.

```text
                    Coordinator
                         |
                   CONTROL ONLY
                         |
            +------------+------------+
            |                         |
         Android                    Linux
            |                         |
            +====== direct TCP =======+
                         |
                  RAW IP DATA PLANE
                         |
             +-----------+-----------+
             |                       |
           IPv4                    IPv6
             |                       |
        TCP / UDP /             TCP / UDP /
        ICMP / ...              ICMPv6 / ...
```

The coordinator handles discovery and coordination.

The peers carry the VPN traffic directly.

The direct TCP stream carries complete raw IPv4 and IPv6 packets.

There is no TCPD data wrapper.

There is no TCPeer packet-length prefix.

There is no TCPeer per-packet DATA header.

```text
TCPeer-specific data-plane framing overhead:

0 bytes
```

---

# Development and AI assistance

TCPeer was developed with the assistance of **OpenAI Codex**.

TCPeer itself is an independent project; Codex was used as a development assistant.

---

# License

TCPeer is licensed under the Apache License 2.0.

See:

```text
LICENSE
```

for the complete license text.
