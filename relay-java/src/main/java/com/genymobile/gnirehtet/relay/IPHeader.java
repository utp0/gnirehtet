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

/**
 * Common abstraction for IPv4 and IPv6 headers.
 */
public interface IPHeader {

    int getVersion();

    boolean isSupported();

    IPv4Header.Protocol getProtocol();

    int getHeaderLength();

    int getTotalLength();

    void setTotalLength(int totalLength);

    InetAddress getSourceAddress();

    InetAddress getDestinationAddress();

    void swapSourceAndDestination();

    ByteBuffer getRaw();

    IPHeader copyTo(ByteBuffer target);

    void computeChecksum();

    /**
     * Read the packet IP version, assuming that an IP packet is stored at absolute position 0.
     *
     * @param buffer the buffer
     * @return the IP version, or {@code -1} if not available
     */
    static int readVersion(ByteBuffer buffer) {
        if (buffer.limit() == 0) {
            // buffer is empty
            return -1;
        }
        // version is stored in the 4 first bits
        return (buffer.get(0) & 0xf0) >> 4;
    }

    /**
     * Read the packet length, assuming that an IP packet is stored at absolute position 0.
     *
     * @param buffer the buffer
     * @return the packet length, or {@code -1} if not available
     */
    static int readLength(ByteBuffer buffer) {
        int version = readVersion(buffer);
        if (version == 4) {
            if (buffer.limit() < 4) {
                // buffer does not even contain the length field
                return -1;
            }
            // packet length is 16 bits starting at offset 2
            return Short.toUnsignedInt(buffer.getShort(2));
        }
        if (version == 6) {
            if (buffer.limit() < 6) {
                // buffer does not even contain the payload length field
                return -1;
            }
            // payload length is 16 bits starting at offset 4, plus the fixed header
            return IPv6Header.IPV6_HEADER_LENGTH + Short.toUnsignedInt(buffer.getShort(4));
        }
        return -1;
    }
}
