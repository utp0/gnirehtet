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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;

/**
 * Convert from level 5 to level 3 by appending correct IPv6 and transport headers.
 */
public class IPv6Packetizer implements IPPacketizer {

    private final ByteBuffer buffer = ByteBuffer.allocate(IPPacket.MAX_PACKET_LENGTH);
    private final ByteBuffer payloadBuffer;

    private final IPv6Header responseIPv6Header;
    private final TransportHeader responseTransportHeader;

    public IPv6Packetizer(IPv6Header ipv6Header, TransportHeader transportHeader) {
        responseIPv6Header = ipv6Header.copyTo(buffer);
        responseTransportHeader = transportHeader.copyTo(buffer);
        payloadBuffer = buffer.slice();
    }

    @Override
    public IPHeader getResponseHeader() {
        return responseIPv6Header;
    }

    public IPv6Header getResponseIPv6Header() {
        return responseIPv6Header;
    }

    @Override
    public TransportHeader getResponseTransportHeader() {
        return responseTransportHeader;
    }

    @Override
    public IPv6Packet packetizeEmptyPayload() {
        payloadBuffer.limit(0).position(0);
        return inflate();
    }

    @Override
    public IPv6Packet packetize(ReadableByteChannel channel, int maxChunkSize) throws IOException {
        payloadBuffer.limit(maxChunkSize).position(0);
        int payloadLength = channel.read(payloadBuffer);
        if (payloadLength == -1) {
            return null;
        }
        payloadBuffer.flip();
        return inflate();
    }

    @Override
    public IPv6Packet packetize(ReadableByteChannel channel) throws IOException {
        return packetize(channel, payloadBuffer.capacity());
    }

    private IPv6Packet inflate() {
        int payloadLength = payloadBuffer.remaining();
        buffer.limit(payloadBuffer.arrayOffset() + payloadBuffer.limit()).position(0);

        int ipv6HeaderLength = responseIPv6Header.getHeaderLength();
        int transportHeaderLength = responseTransportHeader.getHeaderLength();
        int totalLength = ipv6HeaderLength + transportHeaderLength + payloadLength;

        responseIPv6Header.setTotalLength(totalLength);
        responseTransportHeader.setPayloadLength(payloadLength);

        // In order to avoid copies, buffer is shared with this IPv6Packet instance that is returned.
        // Don't use it after another call to packetize()!
        IPv6Packet packet = new IPv6Packet(buffer);
        packet.computeChecksums();
        return packet;
    }
}
