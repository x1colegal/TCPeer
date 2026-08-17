import os

import pytest

from tcppeer.tun import TunDevice, TunError


@pytest.mark.parametrize("version", [4, 6])
def test_tun_accepts_both_inner_ip_families(version):
    read_fd, write_fd = os.pipe()
    device = TunDevice("test0", 1400)
    device.fd = write_fd
    packet = bytes((version << 4,)) + b"\0" * 31
    try:
        assert device.write(packet) == len(packet)
        assert os.read(read_fd, len(packet)) == packet
    finally:
        os.close(read_fd)
        device.close()


def test_tun_rejects_non_ip_payload():
    read_fd, write_fd = os.pipe()
    device = TunDevice("test0", 1400)
    device.fd = write_fd
    try:
        with pytest.raises(TunError, match="IPv4 and IPv6"):
            device.write(b"\x10not-an-ip-packet")
    finally:
        os.close(read_fd)
        device.close()
