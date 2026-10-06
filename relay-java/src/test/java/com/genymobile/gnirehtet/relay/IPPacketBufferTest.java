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
public class IPPacketBufferTest {

    private static byte[] concatenate(ByteBuffer... packets) {
        int length = 0;
        for (ByteBuffer packet : packets) {
            length += packet.remaining();
        }
        byte[] result = new byte[length];
        int offset = 0;
        for (ByteBuffer packet : packets) {
            int remaining = packet.remaining();
            packet.get(result, offset, remaining);
            offset += remaining;
        }
        return result;
    }

    @Test
    public void parseMixedPackets() throws IOException {
        byte[] data = concatenate(TestPackets.createIPv4UdpPacket(), TestPackets.createIPv6UdpPacket());
        IPPacketBuffer buffer = new IPPacketBuffer();
        buffer.readFrom(Channels.newChannel(new ByteArrayInputStream(data)));

        IPPacket packet = buffer.asIPPacket();
        Assert.assertNotNull(packet);
        Assert.assertEquals(4, packet.getVersion());
        Assert.assertEquals(32, packet.getRawLength());
        buffer.next();

        packet = buffer.asIPPacket();
        Assert.assertNotNull(packet);
        Assert.assertEquals(6, packet.getVersion());
        Assert.assertEquals(52, packet.getRawLength());
        buffer.next();

        Assert.assertNull(buffer.asIPPacket());
    }

    @Test
    public void parseFragmentedIPv6Packet() throws IOException {
        byte[] data = concatenate(TestPackets.createIPv6UdpPacket());
        IPPacketBuffer buffer = new IPPacketBuffer();
        buffer.readFrom(Channels.newChannel(new ByteArrayInputStream(data, 0, 20)));
        Assert.assertNull(buffer.asIPPacket());

        buffer.readFrom(Channels.newChannel(new ByteArrayInputStream(data, 20, data.length - 20)));
        IPPacket packet = buffer.asIPPacket();
        Assert.assertNotNull(packet);
        Assert.assertEquals(6, packet.getVersion());
        Assert.assertEquals(52, packet.getRawLength());
    }
}
