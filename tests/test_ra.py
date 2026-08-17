import ipaddress
import struct

from tcppeer.ra import build_router_advertisement, internet_checksum


def test_ra_contains_slaac_prefix_and_rdnss_with_valid_checksum():
    source = ipaddress.ip_address("fdfe:cafe:cafe::1")
    destination = ipaddress.ip_address("ff02::1")
    prefix = ipaddress.ip_network("fdfe:cafe:cafe::/64")
    packet = build_router_advertisement(
        source, prefix, 1800, 3600, 86400, ["2606:4700:4700::1111"], destination,
    )
    assert packet[0] >> 4 == 6
    assert packet[6] == 58
    assert packet[40] == 134
    prefix_option = packet[56:88]
    assert prefix_option[0:4] == bytes((3, 4, 64, 0xC0))
    assert prefix.network_address.packed in prefix_option
    assert packet[88] == 25
    body = packet[40:]
    pseudo = source.packed + destination.packed + struct.pack("!I3xB", len(body), 58)
    assert internet_checksum(pseudo + body) == 0
