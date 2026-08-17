import ipaddress
import struct

from tcppeer.dhcp import (
    BOOTREQUEST, DHCP_MAGIC, DHCPACK, DHCPDISCOVER, DHCPOFFER, DHCPNAK,
    DHCPRELEASE, DHCPREQUEST, FIXED, OPTION_CLIENT_ID, OPTION_END,
    OPTION_MESSAGE_TYPE, OPTION_REQUESTED_IP, DhcpServer, parse_message,
)
from tcppeer.state import StateStore


def request(kind, client=b"client-a", requested=None):
    fixed = FIXED.pack(
        BOOTREQUEST, 1, 6, 0, 0x12345678, 0, 0x8000,
        b"\0" * 4, b"\0" * 4, b"\0" * 4, b"\0" * 4,
        b"\x02\x00\x00\x00\x00\x01".ljust(16, b"\0"), b"\0" * 64, b"\0" * 128,
    )
    options = bytes((OPTION_MESSAGE_TYPE, 1, kind, OPTION_CLIENT_ID, len(client))) + client
    if requested is not None:
        options += bytes((OPTION_REQUESTED_IP, 4)) + ipaddress.ip_address(requested).packed
    return fixed + DHCP_MAGIC + options + bytes((OPTION_END,))


def server(tmp_path):
    store = StateStore(tmp_path / "state.db")
    return store, DhcpServer(
        store, ipaddress.ip_network("10.50.0.0/24"), ipaddress.ip_address("10.50.0.1"),
        ipaddress.ip_address("10.50.0.10"), ipaddress.ip_address("10.50.0.20"), 3600,
    )


def test_discover_offer_request_ack_and_renew(tmp_path):
    store, dhcp = server(tmp_path)
    offer = parse_message(dhcp.handle(request(DHCPDISCOVER), now=100))
    assert offer.message_type == DHCPOFFER
    address = offer.yiaddr
    ack = parse_message(dhcp.handle(request(DHCPREQUEST, requested=str(address)), now=110))
    assert ack.message_type == DHCPACK
    assert ack.yiaddr == address
    renewal = parse_message(dhcp.handle(request(DHCPREQUEST), now=120))
    assert renewal.message_type == DHCPACK
    assert store.get_lease("636c69656e742d61").expires_at == 3720
    store.close()


def test_release(tmp_path):
    store, dhcp = server(tmp_path)
    dhcp.handle(request(DHCPDISCOVER), now=100)
    assert dhcp.handle(request(DHCPRELEASE), now=101) is None
    assert store.list_table("leases") == []
    store.close()


def test_requested_address_owned_by_another_client_is_nak(tmp_path):
    store, dhcp = server(tmp_path)
    first_offer = parse_message(dhcp.handle(request(DHCPDISCOVER, client=b"first"), now=100))
    dhcp.handle(request(DHCPREQUEST, client=b"first", requested=str(first_offer.yiaddr)), now=101)
    conflict = parse_message(
        dhcp.handle(request(DHCPREQUEST, client=b"second", requested=str(first_offer.yiaddr)), now=102)
    )
    assert conflict.message_type == DHCPNAK
    assert len(store.list_table("leases")) == 1
    store.close()
