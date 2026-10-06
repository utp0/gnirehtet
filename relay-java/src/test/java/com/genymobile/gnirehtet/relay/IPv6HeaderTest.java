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

import java.net.InetAddress;
import java.nio.ByteBuffer;

@SuppressWarnings("checkstyle:MagicNumber")
public class IPv6HeaderTest {

    @Test
    public void parseHeader() {
        ByteBuffer raw = TestPackets.createIPv6UdpPacket().duplicate();
        IPv6Header header = new IPv6Header(raw);

        Assert.assertTrue(header.isSupported());
        Assert.assertEquals(6, header.getVersion());
        Assert.assertEquals(40, header.getHeaderLength());
        Assert.assertEquals(52, header.getTotalLength());
        Assert.assertEquals(IPv4Header.Protocol.UDP, header.getProtocol());
        Assert.assertArrayEquals(TestPackets.IPV6_SOURCE, header.getSourceAddress().getAddress());
        Assert.assertArrayEquals(TestPackets.IPV6_DESTINATION, header.getDestinationAddress().getAddress());
    }

    @Test
    public void swapSourceAndDestination() {
        ByteBuffer raw = TestPackets.createIPv6UdpPacket().duplicate();
        IPv6Header header = new IPv6Header(raw);
        header.swapSourceAndDestination();

        Assert.assertArrayEquals(TestPackets.IPV6_DESTINATION, header.getSourceAddress().getAddress());
        Assert.assertArrayEquals(TestPackets.IPV6_SOURCE, header.getDestinationAddress().getAddress());

        ByteBuffer addresses = raw.duplicate();
        addresses.position(8);
        byte[] source = new byte[16];
        byte[] destination = new byte[16];
        addresses.get(source);
        addresses.get(destination);
        Assert.assertArrayEquals(TestPackets.IPV6_DESTINATION, source);
        Assert.assertArrayEquals(TestPackets.IPV6_SOURCE, destination);
    }

    @Test
    public void readVersionAndLength() {
        ByteBuffer raw = TestPackets.createIPv6UdpPacket();
        Assert.assertEquals(6, IPHeader.readVersion(raw));
        Assert.assertEquals(52, IPHeader.readLength(raw));

        ByteBuffer shortHeader = ByteBuffer.wrap(new byte[] {(byte) (6 << 4), 0, 0});
        Assert.assertEquals(6, IPHeader.readVersion(shortHeader));
        Assert.assertEquals(-1, IPHeader.readLength(shortHeader));

        Assert.assertEquals(-1, IPHeader.readVersion(ByteBuffer.allocate(0)));
    }

    @Test
    public void sourceAddressIsIPv6() {
        IPv6Header header = new IPv6Header(TestPackets.createIPv6UdpPacket().duplicate());
        InetAddress address = header.getSourceAddress();
        Assert.assertTrue(address instanceof java.net.Inet6Address);
    }
}
