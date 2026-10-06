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

@SuppressWarnings("checkstyle:MagicNumber")
public class UDPHeader implements TransportHeader {

    private static final int UDP_HEADER_LENGTH = 8;

    private final ByteBuffer raw;
    private int sourcePort;
    private int destinationPort;

    public UDPHeader(ByteBuffer raw) {
        this.raw = raw;
        raw.limit(UDP_HEADER_LENGTH);
        sourcePort = Short.toUnsignedInt(raw.getShort(0));
        destinationPort = Short.toUnsignedInt(raw.getShort(2));
    }

    @Override
    public int getSourcePort() {
        return sourcePort;
    }

    @Override
    public int getDestinationPort() {
        return destinationPort;
    }

    @Override
    public void setSourcePort(int sourcePort) {
        this.sourcePort = sourcePort;
        raw.putShort(0, (short) sourcePort);
    }

    @Override
    public void setDestinationPort(int destinationPort) {
        this.destinationPort = destinationPort;
        raw.putShort(2, (short) destinationPort);
    }

    @Override
    public int getHeaderLength() {
        return UDP_HEADER_LENGTH;
    }

    @Override
    public void setPayloadLength(int payloadLength) {
        int length = getHeaderLength() + payloadLength;
        raw.putShort(4, (short) length);
    }

    @Override
    public ByteBuffer getRaw() {
        raw.rewind();
        return raw.slice();
    }

    @Override
    public UDPHeader copyTo(ByteBuffer target) {
        raw.rewind();
        ByteBuffer slice = Binary.slice(target, target.position(), getHeaderLength());
        target.put(raw);
        return new UDPHeader(slice);
    }

    @Override
    public void computeChecksum(IPHeader ipHeader, ByteBuffer payload) {
        if (ipHeader instanceof IPv6Header) {
            computeChecksumV6((IPv6Header) ipHeader, payload);
        } else {
            // disable checksum validation
            raw.putShort(6, (short) 0);
        }
    }

    private void computeChecksumV6(IPv6Header ipv6Header, ByteBuffer payload) {
        // IPv6 requires a UDP checksum (RFC 8200); 0 means "no checksum" is not allowed
        byte[] rawArray = raw.array();
        int rawOffset = raw.arrayOffset();

        byte[] payloadArray = payload.array();
        int payloadOffset = payload.arrayOffset();

        byte[] source = ipv6Header.getSource();
        byte[] destination = ipv6Header.getDestination();
        int length = ipv6Header.getTotalLength() - ipv6Header.getHeaderLength();

        int sum = 0;
        for (int i = 0; i < 16; i += 2) {
            sum += ((source[i] & 0xff) << 8) | (source[i + 1] & 0xff);
            sum += ((destination[i] & 0xff) << 8) | (destination[i + 1] & 0xff);
        }
        sum += length >>> 16;
        sum += length & 0xffff;
        sum += IPv4Header.Protocol.UDP.getNumber();

        // reset checksum field
        raw.putShort(6, (short) 0);

        for (int i = 0; i < UDP_HEADER_LENGTH / 2; ++i) {
            sum += ((rawArray[rawOffset + 2 * i] & 0xff) << 8) | (rawArray[rawOffset + 2 * i + 1] & 0xff);
        }

        int payloadLength = length - UDP_HEADER_LENGTH;
        assert payloadLength == payload.limit() : "Payload length does not match";
        for (int i = 0; i < payloadLength / 2; ++i) {
            sum += ((payloadArray[payloadOffset + 2 * i] & 0xff) << 8) | (payloadArray[payloadOffset + 2 * i + 1] & 0xff);
        }
        if (payloadLength % 2 != 0) {
            sum += (payloadArray[payloadOffset + payloadLength - 1] & 0xff) << 8;
        }

        while ((sum & ~0xffff) != 0) {
            sum = (sum & 0xffff) + (sum >> 16);
        }
        int checksum = ~sum & 0xffff;
        raw.putShort(6, (short) (checksum == 0 ? 0xffff : checksum));
    }
}
