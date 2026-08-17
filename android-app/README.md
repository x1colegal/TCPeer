# TCPeer Android Client

The Android client is a native Kotlin application using `VpnService`, Jetpack
Compose, Material 3, and Material You dynamic color.

## Behavior

- Requests Android VPN consent before starting.
- Accepts a DNS name, IPv4 address, or IPv6 address for the coordinator.
- Runs the VPN as a foreground service with a persistent notification.
- Protects coordinator and direct TCP sockets from the VPN route.
- Protects the Secret Key with HMAC-SHA256 challenge response.
- Keeps all other ASCII control fields and VPN traffic cleartext.
- Uses binary DATA frames for inner IPv4 and IPv6 packets.
- Negotiates stateful IPv4 by sending DHCP packets inside DATA frames.
- Derives an IPv6 SLAAC address from the server Router Advertisement.
- Performs coordinated direct TCP simultaneous-open.
- Never opens a UDP socket and never uses relay, QUIC, WebRTC, or TLS.
- Treats TCP6 failure as final when both peers have usable IPv6.

The application displays `Disconnected`, `Connecting`, `TCP6 Direct`, `TCP4
Direct`, or `No Direct Connection`. It never displays a relay state.

## Build

```console
export ANDROID_HOME="$HOME/Android"
./gradlew :android-app:testDebugUnitTest :android-app:assembleDebug
```

The installable debug APK is generated at
`android-app/build/outputs/apk/debug/android-app-debug.apk`.
