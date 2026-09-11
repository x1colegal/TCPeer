import asyncio
import unittest

from tcppeer.protocol import ProtocolError, encode_data, encode_data_plane_control, read_data


class TpfEncodingTests(unittest.TestCase):
    def test_ipv6_trailing_tun_bytes_are_not_put_on_stream(self):
        packet = bytearray(80)
        packet[0] = 0x60
        packet[4:6] = (24).to_bytes(2, "big")
        frame = encode_data(bytes(packet))
        self.assertTrue(frame.startswith(b"TPF/1 DATA\r\nLength: 64\r\n\r\n"))
        self.assertEqual(bytes(packet[:64]), frame.split(b"\r\n\r\n", 1)[1])

    def test_truncated_declared_packet_is_rejected(self):
        packet = bytearray(40)
        packet[0] = 0x60
        packet[4:6] = (24).to_bytes(2, "big")
        with self.assertRaisesRegex(ProtocolError, "truncated IP packet"):
            encode_data(bytes(packet))

    def test_top_level_tpcp_is_not_inside_tpf(self):
        packet = bytearray(40)
        packet[0] = 0x60
        wire = encode_data_plane_control("PONG") + encode_data(bytes(packet))
        self.assertTrue(wire.startswith(b"TPCP/2 PONG\r\n\r\nTPF/1 DATA\r\n"))

        async def parse():
            reader = asyncio.StreamReader()
            reader.feed_data(wire)
            reader.feed_eof()
            return await read_data(reader)

        self.assertEqual(bytes(packet), asyncio.run(parse()))


if __name__ == "__main__":
    unittest.main()
