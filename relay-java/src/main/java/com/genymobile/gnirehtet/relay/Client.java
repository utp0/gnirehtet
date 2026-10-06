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
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

public class Client {

    private static final String TAG = Client.class.getSimpleName();

    private static final int MAX_COMPRESSION_CHUNK_LENGTH = 16 * 1024;
    private static final int MAX_DECOMPRESSED_FRAME_LENGTH = 2 * IPv4Packet.MAX_PACKET_LENGTH;

    private static int nextId = 0;

    private final int id;
    private final SocketChannel clientChannel;
    private final SelectionKey selectionKey;
    private final CloseListener<Client> closeListener;
    private int interests;

    private final int compressionAlgorithm;

    private final IPv4PacketBuffer clientToNetwork = new IPv4PacketBuffer();
    private final StreamBuffer networkToClient = new StreamBuffer(16 * IPv4Packet.MAX_PACKET_LENGTH);
    private final Router router;

    private final List<PacketSource> pendingPacketSources = new ArrayList<>();

    // store the handshake (client id or compression handshake) to send to the client before relaying any data
    private ByteBuffer pendingHandshake;

    // compressed frame not fully written to the client yet
    private ByteBuffer pendingFrame;
    private final Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION);
    private final Inflater inflater = new Inflater();

    // state of the frame currently being read from the client
    private final ByteBuffer frameHeader = ByteBuffer.allocate(4);
    private ByteBuffer framePayload;
    private boolean frameRaw;
    private final ByteBuffer decompressed = ByteBuffer.allocate(MAX_DECOMPRESSED_FRAME_LENGTH);
    private boolean decompressedReady;

    public Client(Selector selector, SocketChannel clientChannel, CloseListener<Client> closeListener) throws ClosedChannelException {
        this(selector, clientChannel, closeListener, TunnelCompression.ALGORITHM_NONE);
    }

    public Client(Selector selector, SocketChannel clientChannel, CloseListener<Client> closeListener, int compressionAlgorithm)
            throws ClosedChannelException {
        id = nextId++;
        this.clientChannel = clientChannel;
        this.compressionAlgorithm = compressionAlgorithm;
        router = new Router(this, selector);
        pendingHandshake = createHandshakeBuffer(id, compressionAlgorithm);

        SelectionHandler selectionHandler = (selectionKey) -> {
            if (selectionKey.isValid() && selectionKey.isWritable()) {
                processSend();
            }
            if (selectionKey.isValid() && selectionKey.isReadable()) {
                processReceive();
            }
            if (selectionKey.isValid()) {
                updateInterests();
            }
        };
        // on start, we are interested only in writing (we must first send the handshake)
        interests = SelectionKey.OP_WRITE;
        selectionKey = clientChannel.register(selector, interests, selectionHandler);

        this.closeListener = closeListener;
    }

    private static ByteBuffer createIntBuffer(int value) {
        final int intSize = 4;
        ByteBuffer buffer = ByteBuffer.allocate(intSize);
        buffer.putInt(value);
        buffer.flip();
        return buffer;
    }

    private static ByteBuffer createHandshakeBuffer(int id, int algorithm) {
        if (algorithm == TunnelCompression.ALGORITHM_NONE) {
            return createIntBuffer(id);
        }
        ByteBuffer buffer = ByteBuffer.allocate(TunnelCompression.HANDSHAKE_LENGTH);
        buffer.putInt(TunnelCompression.MAGIC);
        buffer.put((byte) TunnelCompression.VERSION);
        buffer.put((byte) algorithm);
        buffer.putInt(id);
        buffer.flip();
        return buffer;
    }

    public int getId() {
        return id;
    }

    public Router getRouter() {
        return router;
    }

    private void processReceive() {
        if (!read()) {
            close();
            return;
        }
        pushToNetwork();
    }

    private void processSend() {
        if (mustSendHandshake()) {
            if (!sendHandshake()) {
                close();
            }
            return;
        }
        if (!write()) {
            close();
            return;
        }
        processPending();
    }

    private boolean read() {
        if (compressionAlgorithm == TunnelCompression.ALGORITHM_NONE) {
            return readPlain();
        }
        return readCompressed();
    }

    private boolean readPlain() {
        try {
            return clientToNetwork.readFrom(clientChannel) != -1;
        } catch (IOException e) {
            Log.e(TAG, "Cannot read", e);
            return false;
        }
    }

    private boolean readCompressed() {
        try {
            if (!drainDecompressed()) {
                return true;
            }
            while (true) {
                if (framePayload != null) {
                    int r = clientChannel.read(framePayload);
                    if (r == -1) {
                        return false;
                    }
                    if (framePayload.hasRemaining()) {
                        return true;
                    }
                    framePayload.flip();
                    if (!decodeFrame()) {
                        return false;
                    }
                    framePayload = null;
                    frameHeader.clear();
                    if (!drainDecompressed()) {
                        return true;
                    }
                    continue;
                }

                int r = clientChannel.read(frameHeader);
                if (r == -1) {
                    return false;
                }
                if (frameHeader.hasRemaining()) {
                    return true;
                }
                frameHeader.flip();
                int header = frameHeader.getInt();
                int length = TunnelCompression.frameLength(header);
                if (length < 0 || length > TunnelCompression.MAX_FRAME_LENGTH) {
                    Log.e(TAG, "Invalid tunnel frame length: " + length);
                    return false;
                }
                frameRaw = TunnelCompression.isRawFrame(header);
                framePayload = ByteBuffer.allocate(length);
            }
        } catch (IOException e) {
            Log.e(TAG, "Cannot read compressed frame", e);
            return false;
        }
    }

    private boolean drainDecompressed() {
        if (!decompressedReady) {
            return true;
        }
        clientToNetwork.readFrom(decompressed);
        if (decompressed.hasRemaining()) {
            // the packet buffer is full, keep the remaining bytes for the next event
            decompressed.compact();
            return false;
        }
        decompressed.clear();
        decompressedReady = false;
        return true;
    }

    private boolean decodeFrame() {
        if (frameRaw) {
            if (framePayload.remaining() > decompressed.capacity()) {
                Log.e(TAG, "Raw tunnel frame too large: " + framePayload.remaining());
                return false;
            }
            decompressed.clear();
            decompressed.put(framePayload);
        } else if (!inflateFrame()) {
            return false;
        }
        decompressed.flip();
        decompressedReady = decompressed.hasRemaining();
        if (!decompressedReady) {
            decompressed.clear();
        }
        return true;
    }

    private boolean inflateFrame() {
        decompressed.clear();
        inflater.setInput(framePayload.array(), framePayload.arrayOffset() + framePayload.position(), framePayload.remaining());
        byte[] scratch = new byte[8 * 1024];
        try {
            while (!inflater.needsInput()) {
                int n = inflater.inflate(scratch);
                if (n == 0) {
                    if (inflater.needsDictionary()) {
                        Log.e(TAG, "Inflater needs a dictionary");
                        return false;
                    }
                    break;
                }
                if (decompressed.remaining() < n) {
                    // not enough room: flush what we have and keep going (sane frames always fit)
                    decompressed.flip();
                    clientToNetwork.readFrom(decompressed);
                    decompressed.clear();
                    if (decompressed.remaining() < n) {
                        Log.e(TAG, "Decompressed tunnel frame too large");
                        return false;
                    }
                }
                decompressed.put(scratch, 0, n);
            }
        } catch (DataFormatException e) {
            Log.e(TAG, "Invalid compressed tunnel frame", e);
            return false;
        } finally {
            inflater.reset();
        }
        return true;
    }

    private boolean write() {
        if (compressionAlgorithm == TunnelCompression.ALGORITHM_NONE) {
            return writePlain();
        }
        return writeCompressed();
    }

    private boolean writePlain() {
        try {
            return networkToClient.writeTo(clientChannel) != -1;
        } catch (IOException e) {
            Log.e(TAG, "Cannot write", e);
            return false;
        }
    }

    private boolean writeCompressed() {
        try {
            if (pendingFrame != null) {
                if (clientChannel.write(pendingFrame) == -1) {
                    return false;
                }
                if (!pendingFrame.hasRemaining()) {
                    pendingFrame = null;
                }
                return true;
            }
            if (networkToClient.isEmpty()) {
                return true;
            }
            ByteBuffer chunk = ByteBuffer.allocate(MAX_COMPRESSION_CHUNK_LENGTH);
            networkToClient.copyTo(chunk);
            chunk.flip();
            pendingFrame = compressFrame(chunk);
            return writeCompressed();
        } catch (IOException e) {
            Log.e(TAG, "Cannot write compressed frame", e);
            return false;
        }
    }

    private ByteBuffer compressFrame(ByteBuffer raw) {
        int length = raw.remaining();
        byte[] input = raw.array();
        int offset = raw.arrayOffset() + raw.position();
        byte[] compressed = new byte[length + 64];
        deflater.reset();
        deflater.setInput(input, offset, length);
        deflater.finish();
        int n = deflater.deflate(compressed);
        while (!deflater.finished()) {
            byte[] bigger = new byte[compressed.length * 2];
            System.arraycopy(compressed, 0, bigger, 0, n);
            compressed = bigger;
            n += deflater.deflate(compressed, n, compressed.length - n);
        }

        ByteBuffer frame;
        if (n < length) {
            frame = ByteBuffer.allocate(4 + n);
            frame.putInt(TunnelCompression.frameHeader(false, n));
            frame.put(compressed, 0, n);
        } else {
            frame = ByteBuffer.allocate(4 + length);
            frame.putInt(TunnelCompression.frameHeader(true, length));
            frame.put(input, offset, length);
        }
        frame.flip();
        return frame;
    }

    private boolean mustSendHandshake() {
        return pendingHandshake != null && pendingHandshake.hasRemaining();
    }

    private boolean sendHandshake() {
        assert mustSendHandshake();
        try {
            if (clientChannel.write(pendingHandshake) == -1) {
                Log.w(TAG, "Cannot write handshake #" + id + " (EOF)");
                return false;
            }
            if (!pendingHandshake.hasRemaining()) {
                // we don't need this buffer anymore, release it
                Log.d(TAG, "Handshake #" + id + " sent to client");
                pendingHandshake = null;
            }
            return true;
        } catch (IOException e) {
            Log.e(TAG, "Cannot write handshake #" + id, e);
            return false;
        }
    }

    private void pushToNetwork() {
        IPv4Packet packet;
        while ((packet = clientToNetwork.asIPv4Packet()) != null) {
            router.sendToNetwork(packet);
            clientToNetwork.next();
        }
    }

    private void close() {
        selectionKey.cancel();
        try {
            clientChannel.close();
        } catch (IOException e) {
            Log.e(TAG, "Cannot close client connection", e);
        }
        router.clear();
        closeListener.onClosed(this);
    }

    private void updateInterests() {
        int interestOps = SelectionKey.OP_READ; // we always want to read
        if (!networkToClient.isEmpty() || (pendingFrame != null && pendingFrame.hasRemaining())) {
            interestOps |= SelectionKey.OP_WRITE;
        }
        if (interests != interestOps) {
            // interests must be changed
            interests = interestOps;
            selectionKey.interestOps(interestOps);
        }
    }

    public boolean sendToClient(IPv4Packet packet) {
        if (networkToClient.remaining() < packet.getRawLength()) {
            Log.w(TAG, "Client buffer full");
            return false;
        }
        networkToClient.readFrom(packet.getRaw());
        updateInterests();
        return true;
    }

    public void consume(PacketSource source) {
        IPv4Packet packet = source.get();
        if (sendToClient(packet)) {
            source.next();
            return;
        }
        assert !pendingPacketSources.contains(source);
        pendingPacketSources.add(source);
    }

    private void processPending() {
        Iterator<PacketSource> iterator = pendingPacketSources.iterator();
        while (iterator.hasNext()) {
            PacketSource packetSource = iterator.next();
            IPv4Packet packet = packetSource.get();
            if (sendToClient(packet)) {
                packetSource.next();
                Log.d(TAG, "Pending packet sent to client (" + packet.getRawLength() + ")");
                iterator.remove();
            } else {
                Log.w(TAG, "Pending packet not sent to client (" + packet.getRawLength() + "), client buffer full again");
                return;
            }
        }
    }

    public void cleanExpiredConnections() {
        router.cleanExpiredConnections();
    }
}
