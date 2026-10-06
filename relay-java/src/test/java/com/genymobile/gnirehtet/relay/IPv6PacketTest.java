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

import org.junit.Assert;
import org.junit.Test;

import java.nio.ByteBuffer;

@SuppressWarnings("checkstyle:MagicNumber")
public class IPv6PacketTest {

    @Test
    public void parsePacket() {
        IPv6Packet packet = new IPv6Packet(TestPackets.createIPv6UdpPacket());

        Assert.assertTrue(packet.isValid());
        Assert.assertEquals(6, packet.getVersion());
        Assert.assertEquals(52, packet.getRawLength());
        Assert.assertEquals(IPv4Header.Protocol.UDP, packet.getProtocol());
        Assert.assertEquals(48, packet.getIPHeader().getHeaderLength() + packet.getTransportHeader().getHeaderLength());
        Assert.assertEquals(4, packet.getPayloadLength());

        ByteBuffer payload = packet.getPayload();
        Assert.assertEquals(0x11223344, payload.getInt());
    }

    @Test
    public void computeUdpChecksum() {
        IPv6Packet packet = new IPv6Packet(TestPackets.createIPv6UdpPacket());
        packet.computeChecksums();
        Assert.assertTrue(TestPackets.isChecksumValid(packet));

        // force a change in the payload, the checksum must be updated accordingly
        IPv6Packet other = new IPv6Packet(TestPackets.createIPv6UdpPacket());
        other.getPayload().putInt(0x55667788);
        other.computeChecksums();
        Assert.assertTrue(TestPackets.isChecksumValid(other));
    }

    @Test
    public void swapSourceAndDestination() {
        IPv6Packet packet = new IPv6Packet(TestPackets.createIPv6UdpPacket());
        packet.swapSourceAndDestination();
        Assert.assertArrayEquals(TestPackets.IPV6_DESTINATION, packet.getSourceAddress().getAddress());
        Assert.assertArrayEquals(TestPackets.IPV6_SOURCE, packet.getDestinationAddress().getAddress());
        Assert.assertEquals(1234, packet.getTransportHeader().getDestinationPort());
        Assert.assertEquals(5678, packet.getTransportHeader().getSourcePort());
    }
}
