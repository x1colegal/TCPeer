import pytest

from tcppeer.auth import authentication_proof, proof_matches
from tcppeer.protocol import (
    ControlMessage, DATA_HEADER, DATA_MAGIC, ProtocolError,
    decode_data, encode_data, parse_control,
)


def test_control_is_ascii_cleartext_and_round_trips():
    encoded = ControlMessage("AUTH", {"Network": "home", "Peer-ID": "server"}).encode()
    assert encoded.decode("ascii").endswith("\r\n\r\n")
    assert b"Secret:" not in encoded
    assert parse_control(encoded).get("Peer-ID") == "server"


def test_secret_authentication_proof_hides_secret():
    proof = authentication_proof("visible-secret", "home", "server", "abc123")
    assert len(proof) == 64
    assert "visible-secret" not in proof
    assert proof_matches("visible-secret", "home", "server", "abc123", proof)
    assert not proof_matches("wrong", "home", "server", "abc123", proof)


def test_non_ascii_control_is_rejected():
    with pytest.raises(ProtocolError, match="ASCII"):
        ControlMessage("ERROR", {"Reason": "n\u00e3o"}).encode()


def test_coordinator_control_parser_rejects_data():
    with pytest.raises(ProtocolError, match="forbidden"):
        parse_control(DATA_MAGIC + b"anything\r\n\r\n")


@pytest.mark.parametrize("version", [4, 6])
def test_binary_data_round_trip(version):
    packet = bytes((version << 4,)) + b"\x00" * 63
    frame = encode_data(packet)
    assert frame.startswith(DATA_MAGIC)
    assert packet in frame
    assert decode_data(frame) == packet


def test_data_family_mismatch_is_rejected():
    packet = b"\x45" + b"\0" * 19
    frame = DATA_HEADER.pack(DATA_MAGIC, 1, 6, len(packet)) + packet
    with pytest.raises(ProtocolError, match="family"):
        decode_data(frame)
