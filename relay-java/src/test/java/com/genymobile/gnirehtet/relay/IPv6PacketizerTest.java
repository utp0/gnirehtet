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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;

@SuppressWarnings("checkstyle:MagicNumber")
public class IPv6PacketizerTest {

    @Test
    public void packetizeUdpEmptyPayload() {
        IPv6Packet reference = new IPv6Packet(TestPackets.createIPv6UdpPacket());
        IPv6Packetizer packetizer = new IPv6Packetizer(reference.getIpv6Header(), reference.getTransportHeader());

        IPv6Packet packet = packetizer.packetizeEmptyPayload();
        Assert.assertTrue(packet.isValid());
        Assert.assertEquals(6, packet.getVersion());
        Assert.assertEquals(48, packet.getRawLength());
        Assert.assertEquals(0, packet.getPayloadLength());
        Assert.assertTrue(TestPackets.isChecksumValid(packet));
    }

    @Test
    public void packetizeUdpPayload() throws IOException {
        IPv6Packet reference = new IPv6Packet(TestPackets.createIPv6UdpPacket());
        IPv6Packetizer packetizer = new IPv6Packetizer(reference.getIpv6Header(), reference.getTransportHeader());

        byte[] payload = {0x11, 0x22, 0x33, 0x44, 0x55}; // odd length
        IPv6Packet packet = packetizer.packetize(Channels.newChannel(new ByteArrayInputStream(payload)));
        Assert.assertNotNull(packet);
        Assert.assertEquals(40 + 8 + payload.length, packet.getRawLength());
        Assert.assertEquals(payload.length, packet.getPayloadLength());
        Assert.assertTrue(TestPackets.isChecksumValid(packet));

        ByteBuffer packetPayload = packet.getPayload();
        for (byte b : payload) {
            Assert.assertEquals(b, packetPayload.get());
        }
    }

    @Test
    public void packetizeTcpEmptyPayload() {
        IPv6Packet reference = new IPv6Packet(TestPackets.createIPv6TcpPacket());
        IPv6Packetizer packetizer = new IPv6Packetizer(reference.getIpv6Header(), reference.getTransportHeader());

        IPv6Packet packet = packetizer.packetizeEmptyPayload();
        Assert.assertTrue(packet.isValid());
        Assert.assertEquals(40 + 20, packet.getRawLength());
        Assert.assertEquals(0, packet.getPayloadLength());
        Assert.assertTrue(TestPackets.isChecksumValid(packet));
    }

    @Test
    public void packetizeTcpPayload() throws IOException {
        IPv6Packet reference = new IPv6Packet(TestPackets.createIPv6TcpPacket());
        IPv6Packetizer packetizer = new IPv6Packetizer(reference.getIpv6Header(), reference.getTransportHeader());

        byte[] payload = {0x11, 0x22, 0x33, 0x44, 0x55, 0x66};
        IPv6Packet packet = packetizer.packetize(Channels.newChannel(new ByteArrayInputStream(payload)));
        Assert.assertNotNull(packet);
        Assert.assertEquals(40 + 20 + payload.length, packet.getRawLength());
        Assert.assertEquals(payload.length, packet.getPayloadLength());
        Assert.assertTrue(TestPackets.isChecksumValid(packet));
    }
}
