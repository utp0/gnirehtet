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

package com.genymobile.gnirehtet;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.net.VpnService;
import android.util.Log;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.util.Arrays;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

public final class RelayTunnel implements Tunnel {

    private static final String TAG = RelayTunnel.class.getSimpleName();

    private static final String LOCAL_ABSTRACT_NAME = "gnirehtet";

    private static final int COMPRESSION_MAGIC = 0x474E525A; // "GNRZ"
    private static final int COMPRESSION_VERSION = 1;
    private static final int COMPRESSION_ALGORITHM_NONE = 0;
    private static final int COMPRESSION_ALGORITHM_DEFLATE = 1;
    private static final int MAX_FRAME_LENGTH = 1 << 20;

    private final LocalSocket localSocket = new LocalSocket();

    private DataInputStream inputStream;
    private DataOutputStream outputStream;
    private boolean compressed;

    private final Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION);
    private final Inflater inflater = new Inflater();
    private byte[] frameBuffer = new byte[0];

    private RelayTunnel() {
        // exposed through open() static method
    }

    @SuppressWarnings("unused")
    public static RelayTunnel open(VpnService vpnService) throws IOException {
        Log.d(TAG, "Opening a new relay tunnel...");
        // since we use a local socket, we don't need to protect the socket from the vpnService anymore
        // but this is an implementation detail, so keep the method signature
        return new RelayTunnel();
    }

    public void connect() throws IOException {
        localSocket.connect(new LocalSocketAddress(LOCAL_ABSTRACT_NAME));
        inputStream = new DataInputStream(localSocket.getInputStream());
        outputStream = new DataOutputStream(localSocket.getOutputStream());
        readHandshake();
    }

    /**
     * The relay server is accessible through an "adb reverse" port redirection.
     * <p>
     * If the port redirection is enabled but the relay server is not started, then the call to
     * channel.connect() will succeed, but the first read() will return -1.
     * <p>
     * As a consequence, the connection state of the relay server would be invalid temporarily (we
     * would switch to CONNECTED state then switch back to DISCONNECTED).
     * <p>
     * To avoid this problem, we must actually read from the server, so that an error occurs
     * immediately if the relay server is not accessible.
     * <p>
     * Therefore, the relay server immediately sends the client id. If tunnel compression is
     * enabled, it is sent as a handshake (magic + version + algorithm + client id) instead; if the
     * magic is not recognized, the value is just the legacy client id and the tunnel stays
     * uncompressed.
     *
     * @throws IOException if an I/O error occurs
     */
    private void readHandshake() throws IOException {
        Log.d(TAG, "Requesting client id");
        int value = inputStream.readInt();
        if (value == COMPRESSION_MAGIC) {
            int version = inputStream.readUnsignedByte();
            int algorithm = inputStream.readUnsignedByte();
            int clientId = inputStream.readInt();
            if (version != COMPRESSION_VERSION) {
                throw new IOException("Unsupported tunnel protocol version: " + version);
            }
            compressed = algorithm == COMPRESSION_ALGORITHM_DEFLATE;
            Log.d(TAG, "Connected to the relay server as #" + Binary.unsigned(clientId)
                    + (compressed ? " (deflate compression)" : " (unknown algorithm " + algorithm + ")"));
        } else {
            Log.d(TAG, "Connected to the relay server as #" + Binary.unsigned(value));
        }
    }

    @Override
    public void send(byte[] packet, int len) throws IOException {
        if (GnirehtetService.VERBOSE) {
            Log.v(TAG, "Sending packet: " + Binary.buildPacketString(packet, len));
        }
        if (!compressed) {
            outputStream.write(packet, 0, len);
            return;
        }
        byte[] compressedFrame = compress(packet, len);
        if (compressedFrame.length < len) {
            outputStream.writeInt(compressedFrame.length);
            outputStream.write(compressedFrame);
        } else {
            // incompressible: send the raw frame
            outputStream.writeInt(len | 0x80000000);
            outputStream.write(packet, 0, len);
        }
    }

    private byte[] compress(byte[] data, int len) {
        byte[] buffer = new byte[len + 64];
        deflater.reset();
        deflater.setInput(data, 0, len);
        deflater.finish();
        int n = deflater.deflate(buffer);
        while (!deflater.finished()) {
            byte[] bigger = new byte[buffer.length * 2];
            System.arraycopy(buffer, 0, bigger, 0, n);
            buffer = bigger;
            n += deflater.deflate(buffer, n, buffer.length - n);
        }
        return Arrays.copyOf(buffer, n);
    }

    @Override
    public int receive(byte[] packet) throws IOException {
        int r = compressed ? receiveCompressed(packet) : inputStream.read(packet);
        if (GnirehtetService.VERBOSE && r > 0) {
            Log.v(TAG, "Receiving packet: " + Binary.buildPacketString(packet, r));
        }
        return r;
    }

    @SuppressWarnings("checkstyle:MagicNumber")
    private int receiveCompressed(byte[] packet) throws IOException {
        int header;
        try {
            header = inputStream.readInt();
        } catch (EOFException e) {
            return -1;
        }
        int length = header & 0x7fffffff;
        if (length > MAX_FRAME_LENGTH) {
            throw new IOException("Invalid tunnel frame length: " + length);
        }
        if (frameBuffer.length < length) {
            frameBuffer = new byte[length];
        }
        inputStream.readFully(frameBuffer, 0, length);
        if ((header & 0x80000000) != 0) {
            if (length > packet.length) {
                throw new IOException("Tunnel frame larger than the receive buffer: " + length);
            }
            System.arraycopy(frameBuffer, 0, packet, 0, length);
            return length;
        }
        inflater.setInput(frameBuffer, 0, length);
        try {
            int n = inflater.inflate(packet);
            if (!inflater.needsInput()) {
                throw new IOException("Compressed tunnel frame larger than the receive buffer");
            }
            return n;
        } catch (DataFormatException e) {
            throw new IOException("Invalid compressed tunnel frame", e);
        } finally {
            inflater.reset();
        }
    }

    @Override
    public void close() {
        try {
            if (localSocket.getFileDescriptor() != null) {
                // close the streams to interrupt pending read and writes
                localSocket.shutdownInput();
                localSocket.shutdownOutput();
            }
            localSocket.close();
        } catch (IOException e) {
            // what could we do?
            throw new RuntimeException(e);
        }
    }
}
