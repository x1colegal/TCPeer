import asyncio
import unittest

from tcppeer.protocol import ProtocolError, encode_data, encode_data_plane_control, encode_tpp_control, read_data


class RawIpEncodingTests(unittest.TestCase):
    def test_ipv6_trailing_tun_bytes_are_not_put_on_stream(self):
        packet = bytearray(80)
        packet[0] = 0x60
        packet[4:6] = (24).to_bytes(2, "big")
        wire = encode_data(bytes(packet))
        self.assertEqual(bytes(packet[:64]), wire)

    def test_truncated_declared_packet_is_rejected(self):
        packet = bytearray(40)
        packet[0] = 0x60
        packet[4:6] = (24).to_bytes(2, "big")
        with self.assertRaisesRegex(ProtocolError, "truncated IP packet"):
            encode_data(bytes(packet))

    def test_top_level_tpcp_and_raw_ip_share_the_stream(self):
        packet = bytearray(40)
        packet[0] = 0x60
        wire = encode_data_plane_control("PONG") + encode_data(bytes(packet))
        control = b"TPCP/2 PONG\r\n\r\n"
        self.assertEqual(control, wire[:len(control)])
        self.assertEqual(0x60, wire[len(control)])

        async def parse():
            reader = asyncio.StreamReader()
            reader.feed_data(wire)
            reader.feed_eof()
            return await read_data(reader)

        self.assertEqual(bytes(packet), asyncio.run(parse()))

    def test_tpp_ping_is_answered_inside_tpcp(self):
        ping = encode_tpp_control("TPP-PING", 42, 123456789)
        packet = bytearray(40)
        packet[0] = 0x60

        async def parse():
            reader = asyncio.StreamReader()
            reader.feed_data(ping + encode_data(bytes(packet)))
            reader.feed_eof()
            output = type("Writer", (), {
                "data": bytearray(),
                "write": lambda self, value: self.data.extend(value),
                "drain": lambda self: asyncio.sleep(0),
            })()
            received = []
            parsed = await read_data(
                reader, output, tpp=lambda command, identifier, timestamp: received.append(
                    (command, identifier, timestamp)
                ),
            )
            return parsed, bytes(output.data), received

        parsed, reply, received = asyncio.run(parse())
        self.assertEqual(bytes(packet), parsed)
        self.assertEqual(encode_tpp_control("TPP-PONG", 42, 123456789), reply)
        self.assertEqual([("TPP-PING", 42, 123456789)], received)


if __name__ == "__main__":
    unittest.main()
