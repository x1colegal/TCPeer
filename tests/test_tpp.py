import ipaddress

from tcppeer.tpp import ECHO_REPLY, ECHO_REQUEST, NEXT_HEADER, build_reply, build_tpp, parse_tpp


def test_tpp_ipv6_next_header_99_echo_round_trip():
    source = ipaddress.ip_address("fdfe:cafe:cafe::10")
    destination = ipaddress.ip_address("fdfe:cafe:cafe::1")
    request = build_tpp(source, destination, ECHO_REQUEST, 42, 123456789)
    assert request[6] == NEXT_HEADER == 99
    parsed_request = parse_tpp(request)
    assert parsed_request is not None
    assert parsed_request.source == source
    assert parsed_request.destination == destination

    reply = build_reply(request)
    parsed_reply = parse_tpp(reply or b"")
    assert parsed_reply is not None
    assert parsed_reply.kind == ECHO_REPLY
    assert parsed_reply.source == destination
    assert parsed_reply.destination == source
    assert parsed_reply.identifier == 42
    assert parsed_reply.timestamp_ns == 123456789
