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

/**
 * Optional compression of the byte stream exchanged between the Android client and the relay over
 * the {@code adb reverse} tunnel.
 * <p>
 * Compression is negotiated by the relay: if it is enabled, the relay sends a handshake
 * (magic + version + algorithm + client id) instead of the legacy raw client id. A client which
 * does not recognize the magic ignores it and stays uncompressed, so the protocol remains
 * compatible with the other relay implementations and with older clients.
 * <p>
 * Once compressed, the stream is split into frames:
 *
 * <pre>
 * uint32 header
 *   bit 31 set:   raw frame, payload length = header &amp; 0x7fffffff
 *   bit 31 clear: deflate frame, compressed length = header
 * byte[payload length]
 * </pre>
 */
@SuppressWarnings("checkstyle:MagicNumber")
public final class TunnelCompression {

    public static final int MAGIC = 0x474E525A; // "GNRZ"
    public static final int VERSION = 1;

    public static final int ALGORITHM_NONE = 0;
    public static final int ALGORITHM_DEFLATE = 1;

    /** magic (4) + version (1) + algorithm (1) + client id (4) */
    public static final int HANDSHAKE_LENGTH = 10;

    /** Upper bound for a single frame, to avoid unbounded allocation on invalid input. */
    public static final int MAX_FRAME_LENGTH = 1 << 20;

    private static final String[] ALGORITHM_NAMES = {"none", "deflate"};

    private TunnelCompression() {
        // not instantiable
    }

    public static int algorithmFromName(String name) {
        if ("none".equalsIgnoreCase(name)) {
            return ALGORITHM_NONE;
        }
        if ("deflate".equalsIgnoreCase(name) || "any".equalsIgnoreCase(name)) {
            return ALGORITHM_DEFLATE;
        }
        throw new IllegalArgumentException("Unknown tunnel compression algorithm: \"" + name
                + "\". Available: " + availableAlgorithms());
    }

    public static String algorithmName(int algorithm) {
        if (algorithm >= 0 && algorithm < ALGORITHM_NAMES.length) {
            return ALGORITHM_NAMES[algorithm];
        }
        return "unknown";
    }

    public static String availableAlgorithms() {
        StringBuilder builder = new StringBuilder();
        for (String name : ALGORITHM_NAMES) {
            if (builder.length() > 0) {
                builder.append(", ");
            }
            builder.append(name);
        }
        return builder.toString();
    }

    public static int frameHeader(boolean raw, int length) {
        return raw ? (length | 0x80000000) : length;
    }

    public static boolean isRawFrame(int header) {
        return (header & 0x80000000) != 0;
    }

    public static int frameLength(int header) {
        return header & 0x7fffffff;
    }
}
