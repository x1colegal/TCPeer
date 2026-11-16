# Changelog

All notable changes to TCPeer are documented in this file.

## [1.0-beta.110] - 2026-11-15 — Big Update

### Added
- Pre-open an 8-port direct TCP pool (`7444`–`7451` by default) with persistent `ENDPOINT-QUERY` keepalive mappings and passive listeners on both `IPv4` and `IPv6`, advertising `IPv4-Port-Pool` and `IPv6-Port-Pool` during `REGISTER`.
- Measure NTP-style clock offset (`T1`/`T2`/`T3`/`T4`, selecting the lowest-RTT sample) over coordinator `PING`/`PONG` messages so peers fire simultaneous-open `SYN` packets at a synchronized physical `Start-Ms`.
- Measure new-flow `IPv4-Connect-Ms` during `ENDPOINT-QUERY` and automatically shift the lower-latency peer's `Start-Ms` in the coordinator to compensate asymmetric new-flow delays (such as router queuing or CGNAT latency differences).

### Changed
- Cycle `(family, peer_pair)` across clean pre-warmed ports (`Local-Port` / `Port`) in `PUNCH-GO` whenever a punch fails or drops early, avoiding `TCP_CONNTRACK_CLOSE` (`RST`) poisoning across dual-CGNAT paths.
- Abort `TCP4`/`TCP6` active connect retry loops immediately on `ECONNREFUSED` (`ConnectionRefusedError` / `ConnectException`) instead of hammering a `RST`-poisoned four-tuple.
- Update [README.md](README.md) with the 8-port pool, `ENDPOINT-QUERY` keepalive behavior, clock-offset synchronization, and new-flow latency compensation.

## [1.0-beta.109] - 2026-11-15

### Added
- Stagger coordinator `PUNCH-GO` delays to open server NAT before CGNAT `SYN`, including `peer_id`-based tie-breaking for same-tier Android peers.
- Add `PUNCH-SUCCESS` notification to release coordinator busy punch leases early.

### Changed
- Bind the Android VPN service dynamically to the active underlying network.
- Ensure immediate stream drain on `KEEPALIVE`/`PONG` and `TPP-PONG` responses.

### Fixed
- Fix mesh hole-punching synchronization, older kernel listener fallback, and Android UI connection state reporting.
- Keep the `IPv4` `ENDPOINT-QUERY` mapping socket alive with `PING` keepalive when connected over `IPv6` tunnels, and remove redundant native `TCP_KEEPALIVE` on active TPCP mapping sockets.
- Fix underlay reconnection loop when `IPv6` appears and omit local `IPv6` without a usable public `IPv6`.
- Fix simultaneous-open socket teardown with native `SO_LINGER`.

## [1.0-beta.102] - 2026-11-12

### Added
- Implement host and LAN isolation for the Android VPN client and block unsolicited LAN forward traffic into `TUN` with nftables overlay prefix isolation.
- Add `PORT` retry for `FIN-WAIT` cleanup and align advertised WAN endpoints with discovery.

### Fixed
- Send TCP `RST` on disconnected peer and stale direct socket teardown.
- Pass `Peer-ID` in `AUTH`, align registered public addresses with active endpoints, prefer stable `IPv6` addresses, and format `IPv6` transport endpoints with port brackets across CLI and Android.
- Fix false public `IPv6` advertisement and use `WIFI_MODE_FULL_HIGH_PERF` WifiLock for screen-off stability.

## [1.0-beta.96] - 2026-11-07

### Fixed
- Initialize `_peer_hosts` and `_direct_target_endpoints` on the Linux client and query known peers in `_accept_direct`.
- Resolve accepted direct and mesh peers dynamically via HMAC proof verification.

## [1.0-beta.95] - 2026-11-06

### Added
- Add mutual `HMAC-SHA256` challenge-response authentication (`AUTH` / `AUTH-PROOF`) to direct P2P connections.

### Changed
- Strip redundant fields from the direct P2P `AUTH` handshake and remove unused `Endpoint` and `Transport` fields from the coordinator device directory.
- Keep Linux peer transport and endpoint as `-` until a direct connection is established, and initialize endpoint mapping writers on the Linux client.

## [1.0-beta.94] - 2026-11-06

### Fixed
- Fix closed `OsTcpSocket` address lookups and stale mesh endpoint state.

## [1.0-beta.93] - 2026-11-06

### Fixed
- Require matching `IPv4` prefix length for local LAN candidates.

## [1.0-beta.92] - 2026-11-03

### Fixed
- Fix dual-stack TCP simultaneous open and use public Android `Os` sockets across underlay reconnects.

## [1.0-beta.91] - 2026-11-03

### Fixed
- Fix Android service shutdown and TCP punch retries.

## [1.0-beta.90] - 2026-11-02

### Fixed
- Prevent duplicate Android `TPP-PONG` replies and restore strict data-plane `PONG` liveness.

## [1.0-beta.89] - 2026-11-01

### Fixed
- Keep sleeping mobile TCP sessions established and keep Android VPN sessions responsive in background.

## [1.0-beta.88] - 2026-11-01

### Fixed
- Rediscover `IPv6` when underlay prefixes change.

## [1.0-beta.87] - 2026-10-31

### Fixed
- Keep Android coordinator sessions alive and send coordinator peer lists atomically.

## [1.0-beta.86] - 2026-10-30

### Fixed
- Allow startup during Android VPN handover.

## [1.0-beta.85] - 2026-10-29

### Added
- Bypass TCPeer VPN routing for Android Auto.

## [1.0-beta.84] - 2026-10-27

### Fixed
- Keep Android VPN sessions responsive during sleep and align direct punch retry windows.

## [1.0-beta.83] - 2026-10-24

### Fixed
- Fix Android liveness and underlay selection.

## [1.0-beta.82] - 2026-10-24

### Fixed
- Stabilize direct mesh sessions.

## [1.0-beta.81] - 2026-10-23

### Fixed
- Retry TCP simultaneous-open within the punch window and remove primary listener race during hole punch.

## [1.0-beta.79] - 2026-10-20

### Fixed
- Stabilize coordinated simultaneous open and prevent data-plane liveness deadlocks.

## [1.0-beta.77] - 2026-10-16

### Changed
- Release TCPeer 1.0 Beta 77 (`PeerNet` isolation from LAN).

## [1.0-beta.72] - 2026-09-25

### Added
- Add legacy Android launcher icons and support Android 6 and newer.

## [1.0-beta.70] - 2026-09-24

### Changed
- Enable Nagle on TCPeer sockets and move TPP monitoring into a bottom sheet.

### Fixed
- Recover direct peers, Android mesh, and underlay handovers across network changes.

## [1.0-beta.66] - 2026-09-21

### Added
- Integrate `TPP-PING`/`TPP-PONG` into TPCP direct streams.

## [1.0-beta.59 - 1.0-beta.65] - 2026-09-20

### Fixed
- Finalize Android VPN teardown reliably across Android 9+, make disconnect authoritative, close TUN stream owners on disconnect, disable always-on VPN retention, and prevent stale VPN resurrection.
