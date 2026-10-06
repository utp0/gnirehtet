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

public class IPPacketBuffer {

    private final ByteBuffer buffer = ByteBuffer.allocate(IPPacket.MAX_PACKET_LENGTH);

    public int readFrom(ReadableByteChannel channel) throws IOException {
        return channel.read(buffer);
    }

    /**
     * Append data from the given buffer, without consuming more than the available space.
     *
     * @param source the source buffer
     * @return the number of bytes actually copied
     */
    public int readFrom(ByteBuffer source) {
        int count = Math.min(source.remaining(), buffer.remaining());
        int limit = source.limit();
        source.limit(source.position() + count);
        buffer.put(source);
        source.limit(limit);
        return count;
    }

    private int getAvailablePacketLength() {
        int version = IPHeader.readVersion(buffer);
        if (version != 4 && version != 6) {
            // no packet, or unsupported version
            return 0;
        }
        int length = IPHeader.readLength(buffer);
        if (length == -1) {
            // no packet
            return 0;
        }
        if (length > buffer.remaining()) {
            // no full packet available
            return 0;
        }
        return length;
    }

    public IPPacket asIPPacket() {
        buffer.flip();
        int length = getAvailablePacketLength();
        if (length == 0) {
            buffer.compact();
            return null;
        }
        int limit = buffer.limit();
        buffer.limit(length).position(0);
        ByteBuffer packetBuffer = buffer.slice();
        buffer.limit(limit).position(length);
        // In order to avoid copies, packetBuffer is shared with the IPPacket instance that is returned.
        // Don't use it after another call to next()!
        int version = IPHeader.readVersion(packetBuffer);
        if (version == 6) {
            return new IPv6Packet(packetBuffer);
        }
        return new IPv4Packet(packetBuffer);
    }

    public void next() {
        buffer.compact();
    }
}
