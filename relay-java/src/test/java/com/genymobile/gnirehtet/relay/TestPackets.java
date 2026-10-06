/*
 * Copyright (C) 2017 Genymobile
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.genymobile.gnirehtet.relay;

import java.nio.ByteBuffer;

final class TestPackets {

    static final byte[] IPV6_SOURCE = {(byte) 0xfd, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 2}; // fd00::2
    static final byte[] IPV6_DESTINATION = {0x20, 1, 0x0d, (byte) 0xb8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1}; // 2001:db8::1

    private TestPackets() {
        // not instantiable
    }

    static ByteBuffer createIPv4UdpPacket() {
        ByteBuffer buffer = ByteBuffer.allocate(32);

        buffer.put((byte) ((4 << 4) | 5)); // versionAndIHL
        buffer.put((byte) 0); // ToS
        buffer.putShort((short) 32); // total length 20 + 8 + 4
        buffer.putInt(0); // IdFlagsFragmentOffset
        buffer.put((byte) 0); // TTL
        buffer.put((byte) 17); // protocol (UDP)
        buffer.putShort((short) 0); // checksum
        buffer.putInt(0x12345678); // source address
        buffer.putInt(0x42424242); // destination address

        buffer.putShort((short) 1234); // source port
        buffer.putShort((short) 5678); // destination port
        buffer.putShort((short) 12); // length
        buffer.putShort((short) 0); // checksum

        buffer.putInt(0x11223344); // payload

        buffer.flip();
        return buffer;
    }

    static ByteBuffer createIPv6UdpPacket() {
        ByteBuffer buffer = ByteBuffer.allocate(52);

        buffer.put((byte) (6 << 4)); // version
        buffer.put((byte) 0); // traffic class + flow label (high)
        buffer.putShort((short) 0); // flow label (remaining)
        buffer.putShort((short) 12); // payload length
        buffer.put((byte) 17); // next header (UDP)
        buffer.put((byte) 64); // hop limit
        buffer.put(IPV6_SOURCE);
        buffer.put(IPV6_DESTINATION);

        buffer.putShort((short) 1234); // source port
        buffer.putShort((short) 5678); // destination port
        buffer.putShort((short) 12); // length
        buffer.putShort((short) 0); // checksum

        buffer.putInt(0x11223344); // payload

        buffer.flip();
        return buffer;
    }

    static ByteBuffer createIPv6TcpPacket() {
        ByteBuffer buffer = ByteBuffer.allocate(40 + 20 + 4);

        buffer.put((byte) (6 << 4)); // version
        buffer.put((byte) 0); // traffic class + flow label (high)
        buffer.putShort((short) 0); // flow label (remaining)
        buffer.putShort((short) 24); // payload length (20 + 4)
        buffer.put((byte) 6); // next header (TCP)
        buffer.put((byte) 64); // hop limit
        buffer.put(IPV6_SOURCE);
        buffer.put(IPV6_DESTINATION);

        buffer.putShort((short) 1234); // source port
        buffer.putShort((short) 5678); // destination port
        buffer.putInt(0x11223344); // sequence number
        buffer.putInt(0); // acknowledgement number
        buffer.putShort((short) ((5 << 12) | 0x18)); // data offset 5, flags PSH|ACK
        buffer.putShort((short) 0xffff); // window
        buffer.putShort((short) 0); // checksum
        buffer.putShort((short) 0); // urgent pointer

        buffer.putInt(0x11223344); // payload

        buffer.flip();
        return buffer;
    }

    /**
     * Verify that the transport checksum of the packet is valid (the sum of the pseudo-header,
     * the transport header and the payload, checksum included, must be 0xffff).
     */
    static boolean isChecksumValid(IPPacket packet) {
        IPHeader ipHeader = packet.getIPHeader();
        ByteBuffer raw = packet.getRaw();
        byte[] transport = new byte[packet.getRawLength() - ipHeader.getHeaderLength()];
        raw.position(ipHeader.getHeaderLength());
        raw.get(transport);

        long sum = 0;
        byte[] source = ipHeader.getSourceAddress().getAddress();
        byte[] destination = ipHeader.getDestinationAddress().getAddress();
        if (ipHeader.getVersion() == 6) {
            for (int i = 0; i < 16; i += 2) {
                sum += ((source[i] & 0xff) << 8) | (source[i + 1] & 0xff);
                sum += ((destination[i] & 0xff) << 8) | (destination[i + 1] & 0xff);
            }
            int length = transport.length;
            sum += length >>> 16;
            sum += length & 0xffff;
        } else {
            sum += ((source[0] & 0xff) << 8) | (source[1] & 0xff);
            sum += ((source[2] & 0xff) << 8) | (source[3] & 0xff);
            sum += ((destination[0] & 0xff) << 8) | (destination[1] & 0xff);
            sum += ((destination[2] & 0xff) << 8) | (destination[3] & 0xff);
            sum += transport.length;
        }
        sum += packet.getProtocol().getNumber();

        for (int i = 0; i + 1 < transport.length; i += 2) {
            sum += ((transport[i] & 0xff) << 8) | (transport[i + 1] & 0xff);
        }
        if ((transport.length & 1) != 0) {
            sum += (transport[transport.length - 1] & 0xff) << 8;
        }
        while ((sum & 0xffff0000L) != 0) {
            sum = (sum & 0xffff) + (sum >> 16);
        }
        return (sum & 0xffff) == 0xffff;
    }
}
