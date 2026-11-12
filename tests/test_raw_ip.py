import asyncio
import struct
import unittest

from tcppeer.packet import build_tcp_reset
from tcppeer.protocol import ProtocolError, encode_data, encode_data_plane_control, encode_tpp_control, read_data
from tcppeer.ra import internet_checksum


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
                "drain": lambda self: (_ for _ in ()).throw(
                    AssertionError("the socket reader must not wait for write backpressure")
                ),
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

    def test_build_tcp_reset_ipv4_syn(self):
        # IPv4 header (20 bytes) + TCP SYN (20 bytes)
        src_ip = bytes([192, 168, 1, 100])
        dst_ip = bytes([10, 0, 0, 2])
        tcp = bytearray(20)
        struct.pack_into("!HHIIBBHHH", tcp, 0, 12345, 80, 1000, 0, 5 << 4, 0x02, 65535, 0, 0)
        ip = bytearray(20)
        struct.pack_into("!BBHHHBBH4s4s", ip, 0, 0x45, 0, 40, 1, 0x4000, 64, 6, 0, src_ip, dst_ip)
        rst = build_tcp_reset(bytes(ip) + bytes(tcp))
        self.assertIsNotNone(rst)
        self.assertEqual(len(rst), 40)
        # Check IP
        self.assertEqual(rst[12:16], dst_ip)
        self.assertEqual(rst[16:20], src_ip)
        self.assertEqual(rst[9], 6)
        # Check TCP
        src_port, dst_port, seq, ack = struct.unpack_from("!HHII", rst, 20)
        self.assertEqual(src_port, 80)
        self.assertEqual(dst_port, 12345)
        self.assertEqual(seq, 0)
        self.assertEqual(ack, 1001)  # SYN consumed 1 seq
        flags = rst[33]
        self.assertEqual(flags, 0x14)  # RST | ACK

    def test_build_tcp_reset_ipv4_ack_data(self):
        # IPv4 header (20 bytes) + TCP ACK+PSH (20 bytes) + 10 bytes payload = 50 bytes
        src_ip = bytes([192, 168, 1, 100])
        dst_ip = bytes([10, 0, 0, 2])
        payload = b"0123456789"
        tcp = bytearray(20)
        struct.pack_into("!HHIIBBHHH", tcp, 0, 12345, 80, 1000, 500, 5 << 4, 0x18, 65535, 0, 0)
        ip = bytearray(20)
        struct.pack_into("!BBHHHBBH4s4s", ip, 0, 0x45, 0, 50, 1, 0x4000, 64, 6, 0, src_ip, dst_ip)
        rst = build_tcp_reset(bytes(ip) + bytes(tcp) + payload)
        self.assertIsNotNone(rst)
        self.assertEqual(len(rst), 40)
        src_port, dst_port, seq, ack = struct.unpack_from("!HHII", rst, 20)
        self.assertEqual(src_port, 80)
        self.assertEqual(dst_port, 12345)
        self.assertEqual(seq, 500)  # Incoming ACK became SEQ
        self.assertEqual(ack, 0)
        flags = rst[33]
        self.assertEqual(flags, 0x04)  # RST

    def test_build_tcp_reset_ignores_existing_rst(self):
        src_ip = bytes([192, 168, 1, 100])
        dst_ip = bytes([10, 0, 0, 2])
        tcp = bytearray(20)
        struct.pack_into("!HHIIBBHHH", tcp, 0, 12345, 80, 1000, 0, 5 << 4, 0x04, 0, 0, 0)
        ip = bytearray(20)
        struct.pack_into("!BBHHHBBH4s4s", ip, 0, 0x45, 0, 40, 1, 0x4000, 64, 6, 0, src_ip, dst_ip)
        self.assertIsNone(build_tcp_reset(bytes(ip) + bytes(tcp)))

    def test_build_tcp_reset_ipv6_syn(self):
        src_ip = b"\x20\x01\x0d\xb8" + b"\x00" * 12
        dst_ip = b"\x20\x01\x0d\xb8" + b"\x01" * 12
        tcp = bytearray(20)
        struct.pack_into("!HHIIBBHHH", tcp, 0, 54321, 443, 2000, 0, 5 << 4, 0x02, 65535, 0, 0)
        ip = struct.pack("!IHBB16s16s", 6 << 28, 20, 6, 64, src_ip, dst_ip)
        rst = build_tcp_reset(ip + bytes(tcp))
        self.assertIsNotNone(rst)
        self.assertEqual(len(rst), 60)
        self.assertEqual(rst[8:24], dst_ip)
        self.assertEqual(rst[24:40], src_ip)
        src_port, dst_port, seq, ack = struct.unpack_from("!HHII", rst, 40)
        self.assertEqual(src_port, 443)
        self.assertEqual(dst_port, 54321)
        self.assertEqual(seq, 0)
        self.assertEqual(ack, 2001)
        self.assertEqual(rst[53], 0x14)  # RST | ACK


if __name__ == "__main__":
    unittest.main()
