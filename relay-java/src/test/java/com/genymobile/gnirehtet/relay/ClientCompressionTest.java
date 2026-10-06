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

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

@SuppressWarnings("checkstyle:MagicNumber")
public class ClientCompressionTest {

    private static ByteBuffer createMockPacket() {
        ByteBuffer buffer = ByteBuffer.allocate(32);

        buffer.put((byte) ((4 << 4) | 5)); // versionAndIHL
        buffer.put((byte) 0); // ToS
        buffer.putShort((short) 32); // total length 20 + 8 + 4
        buffer.putInt(0); // IdFlagsFragmentOffset
        buffer.put((byte) 0); // TTL
        buffer.put((byte) 17); // protocol (UDP)
        buffer.putShort((short) 0); // checksum
        buffer.putInt(0x0a000002); // source address 10.0.0.2
        buffer.putInt(0x7f000001); // destination address 127.0.0.1

        buffer.putShort((short) 1234); // source port
        buffer.putShort((short) 9); // destination port
        buffer.putShort((short) 12); // length
        buffer.putShort((short) 0); // checksum

        buffer.putInt(0x11223344); // payload

        buffer.flip();
        return buffer;
    }

    private static ByteBuffer compressFrame(byte[] raw) {
        byte[] compressed = new byte[raw.length + 64];
        Deflater deflater = new Deflater();
        try {
            deflater.setInput(raw);
            deflater.finish();
            int n = deflater.deflate(compressed);
            while (!deflater.finished()) {
                byte[] bigger = new byte[compressed.length * 2];
                System.arraycopy(compressed, 0, bigger, 0, n);
                compressed = bigger;
                n += deflater.deflate(compressed, n, compressed.length - n);
            }
            ByteBuffer frame = ByteBuffer.allocate(4 + n);
            frame.putInt(n);
            frame.put(compressed, 0, n);
            frame.flip();
            return frame;
        } finally {
            deflater.end();
        }
    }

    private static byte[] inflate(int header, ByteBuffer payload) throws IOException {
        int length = TunnelCompression.frameLength(header);
        byte[] raw = new byte[length];
        ByteBuffer buffer = ByteBuffer.wrap(raw);
        if (TunnelCompression.isRawFrame(header)) {
            buffer.put(payload);
            return raw;
        }
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(payload.array(), payload.arrayOffset() + payload.position(), payload.remaining());
            int n = inflater.inflate(raw);
            if (n != raw.length || !inflater.needsInput()) {
                throw new IOException("Invalid compressed frame");
            }
            return raw;
        } catch (DataFormatException e) {
            throw new IOException(e);
        } finally {
            inflater.end();
        }
    }

    private static void readFully(SocketChannel channel, ByteBuffer buffer) throws IOException {
        while (buffer.hasRemaining()) {
            if (channel.read(buffer) == -1) {
                throw new IOException("Unexpected EOF");
            }
        }
    }

    private static void pump(Selector selector) throws IOException {
        while (selector.select(100) > 0) {
            for (SelectionKey key : selector.selectedKeys()) {
                ((SelectionHandler) key.attachment()).onReady(key);
            }
            selector.selectedKeys().clear();
        }
    }

    @Test
    public void testCompressedTunnel() throws IOException {
        ServerSocketChannel serverSocketChannel = ServerSocketChannel.open();
        try {
            serverSocketChannel.socket().bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            int port = serverSocketChannel.socket().getLocalPort();
            SocketChannel fakeClient = SocketChannel.open(new InetSocketAddress(InetAddress.getLoopbackAddress(), port));
            SocketChannel accepted = serverSocketChannel.accept();
            try {
                List<Client> closedClients = new ArrayList<>();
                accepted.configureBlocking(false);
                Selector selector = Selector.open();
                Client client = new Client(selector, accepted, closedClients::add, TunnelCompression.ALGORITHM_DEFLATE);

                // the relay sends the compression handshake
                pump(selector);
                ByteBuffer handshake = ByteBuffer.allocate(TunnelCompression.HANDSHAKE_LENGTH);
                readFully(fakeClient, handshake);
                handshake.flip();
                Assert.assertEquals(TunnelCompression.MAGIC, handshake.getInt());
                Assert.assertEquals(TunnelCompression.VERSION, handshake.get());
                Assert.assertEquals(TunnelCompression.ALGORITHM_DEFLATE, handshake.get());
                Assert.assertEquals(client.getId(), handshake.getInt());

                // client -> relay: a compressed frame must be accepted
                ByteBuffer packet = createMockPacket();
                byte[] raw = new byte[packet.remaining()];
                packet.get(raw);
                fakeClient.write(compressFrame(raw));
                pump(selector);
                Assert.assertTrue(closedClients.isEmpty());

                // relay -> client: the packet must be compressed, then decompressed back
                client.sendToClient(new IPv4Packet(ByteBuffer.wrap(raw)));
                pump(selector);
                ByteBuffer frameHeader = ByteBuffer.allocate(4);
                readFully(fakeClient, frameHeader);
                frameHeader.flip();
                int header = frameHeader.getInt();
                ByteBuffer payload = ByteBuffer.allocate(TunnelCompression.frameLength(header));
                readFully(fakeClient, payload);
                payload.flip();
                Assert.assertArrayEquals(raw, inflate(header, payload));

                Assert.assertTrue(closedClients.isEmpty());
                selector.close();
            } finally {
                fakeClient.close();
                accepted.close();
            }
        } finally {
            serverSocketChannel.close();
        }
    }
}
