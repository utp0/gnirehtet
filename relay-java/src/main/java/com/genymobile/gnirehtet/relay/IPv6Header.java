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

import java.net.InetAddress;
import java.nio.ByteBuffer;

@SuppressWarnings("checkstyle:MagicNumber")
public class IPv6Header implements IPHeader {

    public static final int IPV6_HEADER_LENGTH = 40;

    private final ByteBuffer raw;
    private byte version;
    private int payloadLength;
    private IPv4Header.Protocol protocol;
    private final byte[] source = new byte[16];
    private final byte[] destination = new byte[16];
    private InetAddress sourceAddress;
    private InetAddress destinationAddress;

    public IPv6Header(ByteBuffer raw) {
        assert raw.limit() >= IPV6_HEADER_LENGTH : "IPv6 headers length must be at least 40 bytes";
        this.raw = raw;

        version = (byte) ((raw.get(0) & 0xf0) >> 4);

        payloadLength = Short.toUnsignedInt(raw.getShort(4));
        protocol = IPv4Header.Protocol.fromNumber(Short.toUnsignedInt(raw.get(6)));

        ByteBuffer addresses = raw.duplicate();
        addresses.position(8);
        addresses.get(source);
        addresses.get(destination);
        sourceAddress = Net.toInetAddress(source);
        destinationAddress = Net.toInetAddress(destination);

        raw.limit(IPV6_HEADER_LENGTH);
    }

    @Override
    public int getVersion() {
        return version;
    }

    @Override
    public boolean isSupported() {
        return version == 6 && protocol != IPv4Header.Protocol.OTHER;
    }

    @Override
    public IPv4Header.Protocol getProtocol() {
        return protocol;
    }

    @Override
    public int getHeaderLength() {
        return IPV6_HEADER_LENGTH;
    }

    @Override
    public int getTotalLength() {
        return IPV6_HEADER_LENGTH + payloadLength;
    }

    @Override
    public void setTotalLength(int totalLength) {
        payloadLength = totalLength - IPV6_HEADER_LENGTH;
        raw.putShort(4, (short) payloadLength);
    }

    @Override
    public InetAddress getSourceAddress() {
        return sourceAddress;
    }

    @Override
    public InetAddress getDestinationAddress() {
        return destinationAddress;
    }

    public byte[] getSource() {
        return source.clone();
    }

    public byte[] getDestination() {
        return destination.clone();
    }

    @Override
    public void swapSourceAndDestination() {
        byte[] tmp = new byte[16];
        System.arraycopy(source, 0, tmp, 0, 16);
        System.arraycopy(destination, 0, source, 0, 16);
        System.arraycopy(tmp, 0, destination, 0, 16);
        sourceAddress = Net.toInetAddress(source);
        destinationAddress = Net.toInetAddress(destination);

        ByteBuffer addresses = raw.duplicate();
        addresses.position(8);
        addresses.put(source);
        addresses.put(destination);
    }

    @Override
    public ByteBuffer getRaw() {
        raw.rewind();
        return raw.slice();
    }

    @Override
    public IPv6Header copyTo(ByteBuffer target) {
        raw.rewind();
        ByteBuffer slice = Binary.slice(target, target.position(), IPV6_HEADER_LENGTH);
        target.put(raw);
        return new IPv6Header(slice);
    }

    public IPv6Header copy() {
        return new IPv6Header(Binary.copy(raw));
    }

    @Override
    public void computeChecksum() {
        // IPv6 has no header checksum
    }
}
