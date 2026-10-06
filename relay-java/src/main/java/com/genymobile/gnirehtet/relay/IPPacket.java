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
 * Common abstraction for IPv4 and IPv6 packets.
 */
@SuppressWarnings("checkstyle:MagicNumber")
public interface IPPacket {

    int MAX_PACKET_LENGTH = (1 << 16) + 40; // maximum IPv6 packet: 40B header + 64K payload

    int getVersion();

    boolean isValid();

    IPHeader getIPHeader();

    TransportHeader getTransportHeader();

    IPv4Header.Protocol getProtocol();

    InetAddress getSourceAddress();

    InetAddress getDestinationAddress();

    void swapSourceAndDestination();

    ByteBuffer getRaw();

    int getRawLength();

    ByteBuffer getPayload();

    int getPayloadLength();

    void computeChecksums();
}
