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

//! Optional compression of the byte stream exchanged between the Android client and the relay
//! over the `adb reverse` tunnel.
//!
//! Compression is negotiated by the relay: if it is enabled, the relay sends a handshake
//! (magic + version + algorithm + client id) instead of the legacy raw client id. A client which
//! does not recognize the magic ignores it and stays uncompressed, so the protocol remains
//! compatible with the other relay implementations and with older clients.
//!
//! Once compressed, the stream is split into frames:
//!
//! ```text
//! uint32 header
//!   bit 31 set:   raw frame, payload length = header & 0x7fffffff
//!   bit 31 clear: deflate frame, compressed length = header
//! byte[payload length]
//! ```

use flate2::read::ZlibDecoder;
use flate2::write::ZlibEncoder;
use flate2::Compression;
use std::io::{self, Read, Write};

pub const MAGIC: u32 = 0x474E_525A; // "GNRZ"
pub const VERSION: u8 = 1;

pub const ALGORITHM_NONE: u8 = 0;
pub const ALGORITHM_DEFLATE: u8 = 1;

/// magic (4) + version (1) + algorithm (1) + client id (4)
pub const HANDSHAKE_LENGTH: usize = 10;

/// Upper bound for a single frame, to avoid unbounded allocation on invalid input.
pub const MAX_FRAME_LENGTH: usize = 1 << 20;

pub fn algorithm_from_name(name: &str) -> Result<u8, String> {
    match name.to_ascii_lowercase().as_str() {
        "none" => Ok(ALGORITHM_NONE),
        "deflate" | "any" => Ok(ALGORITHM_DEFLATE),
        _ => Err(format!(
            "Unknown tunnel compression algorithm: \"{}\". Available: {}",
            name,
            available_algorithms()
        )),
    }
}

pub fn algorithm_name(algorithm: u8) -> &'static str {
    match algorithm {
        ALGORITHM_NONE => "none",
        ALGORITHM_DEFLATE => "deflate",
        _ => "unknown",
    }
}

pub fn available_algorithms() -> &'static str {
    "none, deflate"
}

pub fn handshake(id: u32, algorithm: u8) -> Vec<u8> {
    if algorithm == ALGORITHM_NONE {
        id.to_be_bytes().to_vec()
    } else {
        let mut handshake = Vec::with_capacity(HANDSHAKE_LENGTH);
        handshake.extend_from_slice(&MAGIC.to_be_bytes());
        handshake.push(VERSION);
        handshake.push(algorithm);
        handshake.extend_from_slice(&id.to_be_bytes());
        handshake
    }
}

pub fn frame_header(raw: bool, length: u32) -> u32 {
    if raw {
        length | 0x8000_0000
    } else {
        length
    }
}

pub fn is_raw_frame(header: u32) -> bool {
    header & 0x8000_0000 != 0
}

pub fn frame_length(header: u32) -> usize {
    (header & 0x7fff_ffff) as usize
}

pub fn compress(raw: &[u8]) -> Vec<u8> {
    let mut encoder = ZlibEncoder::new(Vec::new(), Compression::default());
    encoder
        .write_all(raw)
        .expect("in-memory compression cannot fail");
    encoder.finish().expect("in-memory compression cannot fail")
}

pub fn decompress(payload: &[u8]) -> io::Result<Vec<u8>> {
    let mut decoder = ZlibDecoder::new(payload);
    let mut decompressed = Vec::new();
    decoder.read_to_end(&mut decompressed)?;
    Ok(decompressed)
}

/// Compress a chunk and wrap it into a frame (raw frame if compression does not shrink it).
pub fn compress_frame(raw: &[u8]) -> Vec<u8> {
    let compressed = compress(raw);
    if compressed.len() < raw.len() {
        let mut frame = Vec::with_capacity(4 + compressed.len());
        frame.extend_from_slice(&frame_header(false, compressed.len() as u32).to_be_bytes());
        frame.extend_from_slice(&compressed);
        frame
    } else {
        let mut frame = Vec::with_capacity(4 + raw.len());
        frame.extend_from_slice(&frame_header(true, raw.len() as u32).to_be_bytes());
        frame.extend_from_slice(raw);
        frame
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::convert::TryInto;

    #[test]
    fn frame_header_roundtrip() {
        let header = frame_header(false, 123);
        assert!(!is_raw_frame(header));
        assert_eq!(123, frame_length(header));

        let header = frame_header(true, 456);
        assert!(is_raw_frame(header));
        assert_eq!(456, frame_length(header));
    }

    #[test]
    fn algorithm_names() {
        assert_eq!(ALGORITHM_NONE, algorithm_from_name("none").unwrap());
        assert_eq!(ALGORITHM_DEFLATE, algorithm_from_name("deflate").unwrap());
        assert_eq!(ALGORITHM_DEFLATE, algorithm_from_name("any").unwrap());
        assert!(algorithm_from_name("zstd").is_err());
    }

    #[test]
    fn compress_decompress_roundtrip() {
        let raw = vec![0x45u8; 4096];
        let frame = compress_frame(&raw);
        let header = u32::from_be_bytes(frame[..4].try_into().unwrap());
        assert!(!is_raw_frame(header));
        let decompressed = decompress(&frame[4..]).unwrap();
        assert_eq!(raw, decompressed);
    }

    #[test]
    fn incompressible_uses_raw_frame() {
        let raw: Vec<u8> = (0..=255u8).collect();
        let frame = compress_frame(&raw);
        let header = u32::from_be_bytes(frame[..4].try_into().unwrap());
        assert!(is_raw_frame(header));
        assert_eq!(raw, frame[4..]);
    }

    #[test]
    fn handshake_none_is_raw_id() {
        assert_eq!(42u32.to_be_bytes().to_vec(), handshake(42, ALGORITHM_NONE));
    }

    #[test]
    fn handshake_deflate_format() {
        let handshake = handshake(7, ALGORITHM_DEFLATE);
        assert_eq!(HANDSHAKE_LENGTH, handshake.len());
        assert_eq!(
            MAGIC,
            u32::from_be_bytes(handshake[..4].try_into().unwrap())
        );
        assert_eq!(VERSION, handshake[4]);
        assert_eq!(ALGORITHM_DEFLATE, handshake[5]);
        assert_eq!(7, u32::from_be_bytes(handshake[6..].try_into().unwrap()));
    }
}
