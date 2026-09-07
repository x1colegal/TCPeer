import unittest

from tcppeer.protocol import ProtocolError, encode_data


class RawIpEncodingTests(unittest.TestCase):
    def test_ipv6_trailing_tun_bytes_are_not_put_on_stream(self):
        packet = bytearray(80)
        packet[0] = 0x60
        packet[4:6] = (24).to_bytes(2, "big")
        self.assertEqual(bytes(packet[:64]), encode_data(bytes(packet)))

    def test_truncated_declared_packet_is_rejected(self):
        packet = bytearray(40)
        packet[0] = 0x60
        packet[4:6] = (24).to_bytes(2, "big")
        with self.assertRaisesRegex(ProtocolError, "truncated IP packet"):
            encode_data(bytes(packet))


if __name__ == "__main__":
    unittest.main()
