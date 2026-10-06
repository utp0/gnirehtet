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
import java.net.InetSocketAddress;

public class ConnectionId {

    private final IPv4Header.Protocol protocol;
    private final InetAddress sourceIp;
    private final short sourcePort;
    private final InetAddress destIp;
    private final short destPort;
    private final String idString;

    public ConnectionId(IPv4Header.Protocol protocol, InetAddress sourceIp, short sourcePort, InetAddress destIp, short destPort) {
        this.protocol = protocol;
        this.sourceIp = sourceIp;
        this.sourcePort = sourcePort;
        this.destIp = destIp;
        this.destPort = destPort;

        // compute the String representation only once
        idString = protocol + " " + Net.toString(new InetSocketAddress(sourceIp, Short.toUnsignedInt(sourcePort)))
                + " -> " + Net.toString(new InetSocketAddress(destIp, Short.toUnsignedInt(destPort)));
    }

    public IPv4Header.Protocol getProtocol() {
        return protocol;
    }

    public InetAddress getSourceAddress() {
        return sourceIp;
    }

    public int getSourcePort() {
        return Short.toUnsignedInt(sourcePort);
    }

    public InetAddress getDestinationAddress() {
        return destIp;
    }

    public int getDestinationPort() {
        return Short.toUnsignedInt(destPort);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }

        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        ConnectionId that = (ConnectionId) o;
        return sourceIp.equals(that.sourceIp)
                && sourcePort == that.sourcePort
                && destIp.equals(that.destIp)
                && destPort == that.destPort
                && protocol == that.protocol;
    }

    @Override
    public int hashCode() {
        int result = protocol.hashCode();
        result = 31 * result + sourceIp.hashCode();
        result = 31 * result + (int) sourcePort;
        result = 31 * result + destIp.hashCode();
        result = 31 * result + (int) destPort;
        return result;
    }

    @Override
    public String toString() {
        return idString;
    }

    public static ConnectionId from(IPPacket packet) {
        TransportHeader transportHeader = packet.getTransportHeader();
        return new ConnectionId(packet.getProtocol(), packet.getSourceAddress(), (short) transportHeader.getSourcePort(),
                packet.getDestinationAddress(), (short) transportHeader.getDestinationPort());
    }
}
