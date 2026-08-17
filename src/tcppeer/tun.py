"""Linux layer-3 TUN lifecycle without network transport sockets."""

from __future__ import annotations

import fcntl
import os
from pathlib import Path
import struct
import subprocess

TUNSETIFF = 0x400454CA
IFF_TUN = 0x0001
IFF_NO_PI = 0x1000


class TunError(RuntimeError):
    """Raised when the Linux TUN interface cannot be configured."""


class TunDevice:
    def __init__(self, name: str, mtu: int):
        self.name = name
        self.mtu = mtu
        self.fd: int | None = None

    def open(self) -> "TunDevice":
        fd = os.open("/dev/net/tun", os.O_RDWR | os.O_NONBLOCK)
        request = struct.pack("16sH", self.name.encode("ascii"), IFF_TUN | IFF_NO_PI)
        try:
            result = fcntl.ioctl(fd, TUNSETIFF, request)
        except OSError as exc:
            os.close(fd)
            raise TunError(f"cannot create TUN interface {self.name}: {exc}") from exc
        self.name = result[:16].split(b"\0", 1)[0].decode("ascii")
        self.fd = fd
        return self

    def configure(self, ipv4: str, ipv4_prefix: int, ipv6: str, ipv6_prefix: int) -> None:
        if self.fd is None:
            raise TunError("TUN interface is not open")
        commands = (
            ("ip", "link", "set", "dev", self.name, "mtu", str(self.mtu)),
            ("ip", "address", "replace", f"{ipv4}/{ipv4_prefix}", "dev", self.name),
            ("ip", "-6", "address", "replace", f"{ipv6}/{ipv6_prefix}", "dev", self.name),
            ("ip", "link", "set", "dev", self.name, "up"),
        )
        for command in commands:
            try:
                subprocess.run(command, check=True, capture_output=True, text=True)
            except (OSError, subprocess.CalledProcessError) as exc:
                raise TunError(f"failed to configure {self.name}: {exc}") from exc

    def read(self, size: int = 65535) -> bytes:
        if self.fd is None:
            raise TunError("TUN interface is not open")
        return os.read(self.fd, size)

    def write(self, packet: bytes) -> int:
        if self.fd is None:
            raise TunError("TUN interface is not open")
        if not packet or packet[0] >> 4 not in (4, 6):
            raise TunError("only IPv4 and IPv6 packets can be written to TUN")
        return os.write(self.fd, packet)

    def close(self) -> None:
        if self.fd is not None:
            os.close(self.fd)
            self.fd = None

    def __enter__(self) -> "TunDevice":
        return self.open()

    def __exit__(self, *_args) -> None:
        self.close()
