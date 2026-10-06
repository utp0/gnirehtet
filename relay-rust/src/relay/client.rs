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

use log::*;
use mio::net::TcpStream;
use mio::{Event, PollOpt, Ready, Token};
use std::cell::RefCell;
use std::io::{self, Read, Write};
use std::mem;
use std::net::Shutdown;
use std::rc::Rc;

use super::binary;
use super::close_listener::CloseListener;
use super::ip_packet::IpPacket;
use super::ip_packet_buffer::IpPacketBuffer;
use super::packet_source::PacketSource;
use super::packetizer::MAX_PACKET_LENGTH;
use super::router::Router;
use super::selector::Selector;
use super::stream_buffer::StreamBuffer;
use super::tunnel_compression;

const TAG: &str = "Client";
const MAX_COMPRESSION_CHUNK_LENGTH: usize = 16 * 1024;

pub struct Client {
    id: u32,
    stream: TcpStream,
    interests: Ready,
    token: Token,
    client_to_network: IpPacketBuffer,
    network_to_client: StreamBuffer,
    router: Router,
    close_listener: Box<dyn CloseListener<Client>>,
    closed: bool,
    pending_packet_sources: Vec<Rc<RefCell<dyn PacketSource>>>,

    compression_algorithm: u8,
    // handshake (client id or compression handshake) not fully written to the client yet
    pending_handshake: Vec<u8>,
    pending_handshake_offset: usize,
    // compressed frame not fully written to the client yet
    pending_frame: Vec<u8>,
    pending_frame_offset: usize,
    // state of the frame currently being read from the client
    frame_header: [u8; 4],
    frame_header_offset: usize,
    frame_payload: Vec<u8>,
    frame_payload_offset: usize,
    frame_raw: bool,
    // decompressed bytes not pushed to client_to_network yet
    decompressed: Vec<u8>,
    decompressed_offset: usize,
}

/// Channel for connections to send back data immediately to the client
pub struct ClientChannel<'a> {
    network_to_client: &'a mut StreamBuffer,
    stream: &'a TcpStream,
    token: Token,
    interests: &'a mut Ready,
    has_pending_frame: bool,
}

impl<'a> ClientChannel<'a> {
    fn new(
        network_to_client: &'a mut StreamBuffer,
        stream: &'a TcpStream,
        token: Token,
        interests: &'a mut Ready,
        has_pending_frame: bool,
    ) -> Self {
        Self {
            network_to_client,
            stream,
            token,
            interests,
            has_pending_frame,
        }
    }

    // Functionally equivalent to Client::send_to_client(), except that it does not require to
    // mutably borrow the whole client.
    pub fn send_to_client(
        &mut self,
        selector: &mut Selector,
        ip_packet: &IpPacket,
    ) -> io::Result<()> {
        if ip_packet.length() as usize <= self.network_to_client.remaining() {
            self.network_to_client.read_from(ip_packet.raw());
            self.update_interests(selector);
            Ok(())
        } else {
            warn!(target: TAG, "Client buffer full");
            Err(io::Error::new(
                io::ErrorKind::WouldBlock,
                "Client buffer full",
            ))
        }
    }

    fn update_interests(&mut self, selector: &mut Selector) {
        let ready = if self.network_to_client.is_empty() && !self.has_pending_frame {
            Ready::readable()
        } else {
            Ready::readable() | Ready::writable()
        };
        if *self.interests != ready {
            // interests must be changed
            *self.interests = ready;
            selector
                .reregister(self.stream, self.token, ready, PollOpt::level())
                .expect("Cannot register on poll");
        }
    }
}

impl Client {
    pub fn create(
        id: u32,
        selector: &mut Selector,
        stream: TcpStream,
        close_listener: Box<dyn CloseListener<Client>>,
        compression_algorithm: u8,
    ) -> io::Result<Rc<RefCell<Self>>> {
        // on start, we are interested only in writing (we must first send the handshake)
        let interests = Ready::writable();
        let pending_handshake = tunnel_compression::handshake(id, compression_algorithm);
        let rc = Rc::new(RefCell::new(Self {
            id,
            stream,
            interests,
            token: Token(0), // default value, will be set afterwards
            client_to_network: IpPacketBuffer::new(),
            network_to_client: StreamBuffer::new(16 * MAX_PACKET_LENGTH),
            router: Router::new(),
            closed: false,
            close_listener,
            pending_packet_sources: Vec::new(),
            compression_algorithm,
            pending_handshake,
            pending_handshake_offset: 0,
            pending_frame: Vec::new(),
            pending_frame_offset: 0,
            frame_header: [0; 4],
            frame_header_offset: 0,
            frame_payload: Vec::new(),
            frame_payload_offset: 0,
            frame_raw: false,
            decompressed: Vec::new(),
            decompressed_offset: 0,
        }));

        {
            let mut self_ref = rc.borrow_mut();
            // set client as router owner
            self_ref.router.set_client(Rc::downgrade(&rc));

            let rc2 = rc.clone();
            // must anotate selector type: https://stackoverflow.com/a/44004103/1987178
            let handler =
                move |selector: &mut Selector, event| rc2.borrow_mut().on_ready(selector, event);
            let token =
                selector.register(&self_ref.stream, handler, interests, PollOpt::level())?;
            self_ref.token = token;
        }
        Ok(rc)
    }

    pub fn id(&self) -> u32 {
        self.id
    }

    pub fn router(&mut self) -> &mut Router {
        &mut self.router
    }

    pub fn channel(&mut self) -> ClientChannel {
        let has_pending_frame = self.pending_frame_offset < self.pending_frame.len();
        ClientChannel::new(
            &mut self.network_to_client,
            &self.stream,
            self.token,
            &mut self.interests,
            has_pending_frame,
        )
    }

    fn close(&mut self, selector: &mut Selector) {
        self.closed = true;
        selector.deregister(&self.stream, self.token).unwrap();
        // shutdown only (there is no close), the socket will be closed on drop
        if self.stream.shutdown(Shutdown::Both).is_err() {
            warn!(target: TAG, "Cannot shutdown client socket");
        }
        self.router.clear(selector);
        self.close_listener.on_closed(self);
    }

    fn on_ready(&mut self, selector: &mut Selector, event: Event) {
        #[allow(clippy::match_wild_err_arm)]
        match self.process(selector, event) {
            Ok(_) => (),
            Err(ref err) if err.kind() == io::ErrorKind::WouldBlock => {
                debug!(target: TAG, "Spurious event, ignoring")
            }
            Err(_) => panic!("Unexpected unhandled error"),
        }
    }

    // return Err(err) with err.kind() == io::ErrorKind::WouldBlock on spurious event
    fn process(&mut self, selector: &mut Selector, event: Event) -> io::Result<()> {
        if !self.closed {
            let ready = event.readiness();
            if ready.is_writable() {
                self.process_send(selector)?;
            }
            if !self.closed && ready.is_readable() {
                self.process_receive(selector)?;
            }
            if !self.closed {
                self.update_interests(selector);
            }
        }
        Ok(())
    }

    // return Err(err) with err.kind() == io::ErrorKind::WouldBlock on spurious event
    fn process_send(&mut self, selector: &mut Selector) -> io::Result<()> {
        if self.must_send_handshake() {
            match self.send_handshake() {
                Ok(_) => {
                    if !self.must_send_handshake() {
                        debug!(target: TAG, "Handshake #{} sent to client", self.id);
                    }
                }
                Err(err) => {
                    if err.kind() == io::ErrorKind::WouldBlock {
                        // rethrow
                        return Err(err);
                    }
                    error!(target: TAG, "Cannot write handshake #{}", self.id);
                    self.close(selector);
                }
            }
        } else {
            match self.write() {
                Ok(_) => self.process_pending(selector),
                Err(err) => {
                    error!(target: TAG, "Cannot write: [{:?}] {}", err.kind(), err);
                    self.close(selector);
                }
            }
        }
        Ok(())
    }

    // return Err(err) with err.kind() == io::ErrorKind::WouldBlock on spurious event
    fn process_receive(&mut self, selector: &mut Selector) -> io::Result<()> {
        match self.read() {
            Ok(true) => self.push_to_network(selector),
            Ok(false) => {
                debug!(target: TAG, "EOF reached");
                self.close(selector);
            }
            Err(err) => {
                if err.kind() == io::ErrorKind::WouldBlock {
                    // rethrow
                    return Err(err);
                }
                error!(target: TAG, "Cannot read: [{:?}] {}", err.kind(), err);
                self.close(selector);
            }
        }
        Ok(())
    }

    pub fn send_to_client(
        &mut self,
        selector: &mut Selector,
        ip_packet: &IpPacket,
    ) -> io::Result<()> {
        if ip_packet.length() as usize <= self.network_to_client.remaining() {
            self.network_to_client.read_from(ip_packet.raw());
            self.update_interests(selector);
            Ok(())
        } else {
            warn!(target: TAG, "Client buffer full");
            Err(io::Error::new(
                io::ErrorKind::WouldBlock,
                "Client buffer full",
            ))
        }
    }

    pub fn register_pending_packet_source(&mut self, source: Rc<RefCell<dyn PacketSource>>) {
        self.pending_packet_sources.push(source);
    }

    fn send_handshake(&mut self) -> io::Result<()> {
        assert!(self.must_send_handshake());
        let w = self
            .stream
            .write(&self.pending_handshake[self.pending_handshake_offset..])?;
        self.pending_handshake_offset += w;
        Ok(())
    }

    fn update_interests(&mut self, selector: &mut Selector) {
        self.channel().update_interests(selector);
    }

    fn read(&mut self) -> io::Result<bool> {
        if self.compression_algorithm == tunnel_compression::ALGORITHM_NONE {
            self.client_to_network.read_from(&mut self.stream)
        } else {
            self.read_compressed()
        }
    }

    // Read (at most) one complete frame, and decompress it. Non-blocking.
    fn read_compressed(&mut self) -> io::Result<bool> {
        // first, push the bytes decompressed by a previous call
        self.drain_decompressed();

        if self.frame_header_offset < 4 {
            while self.frame_header_offset < 4 {
                let r = self
                    .stream
                    .read(&mut self.frame_header[self.frame_header_offset..])?;
                if r == 0 {
                    return Ok(false);
                }
                self.frame_header_offset += r;
            }
            let header = u32::from_be_bytes(self.frame_header);
            let length = tunnel_compression::frame_length(header);
            if length > tunnel_compression::MAX_FRAME_LENGTH {
                return Err(io::Error::new(
                    io::ErrorKind::InvalidData,
                    "Invalid tunnel frame length",
                ));
            }
            self.frame_raw = tunnel_compression::is_raw_frame(header);
            self.frame_payload = vec![0; length];
            self.frame_payload_offset = 0;
        }

        while self.frame_payload_offset < self.frame_payload.len() {
            let r = self
                .stream
                .read(&mut self.frame_payload[self.frame_payload_offset..])?;
            if r == 0 {
                return Ok(false);
            }
            self.frame_payload_offset += r;
        }

        // frame complete
        self.decode_frame()?;
        self.drain_decompressed();
        self.frame_header = [0; 4];
        self.frame_header_offset = 0;
        self.frame_payload.clear();
        self.frame_payload_offset = 0;
        Ok(true)
    }

    fn decode_frame(&mut self) -> io::Result<()> {
        if self.frame_raw {
            self.decompressed = mem::take(&mut self.frame_payload);
        } else {
            self.decompressed = tunnel_compression::decompress(&self.frame_payload)?;
        }
        self.decompressed_offset = 0;
        Ok(())
    }

    fn drain_decompressed(&mut self) {
        if self.decompressed_offset < self.decompressed.len() {
            let copied = self
                .client_to_network
                .read_from_slice(&self.decompressed[self.decompressed_offset..]);
            self.decompressed_offset += copied;
            if self.decompressed_offset == self.decompressed.len() {
                self.decompressed.clear();
                self.decompressed_offset = 0;
            }
        }
    }

    fn write(&mut self) -> io::Result<()> {
        if self.compression_algorithm == tunnel_compression::ALGORITHM_NONE {
            self.network_to_client.write_to(&mut self.stream)?;
            Ok(())
        } else {
            self.write_compressed()
        }
    }

    fn write_compressed(&mut self) -> io::Result<()> {
        if self.pending_frame_offset < self.pending_frame.len() {
            let w = self
                .stream
                .write(&self.pending_frame[self.pending_frame_offset..])?;
            self.pending_frame_offset += w;
            if self.pending_frame_offset == self.pending_frame.len() {
                self.pending_frame.clear();
                self.pending_frame_offset = 0;
            }
            return Ok(());
        }

        if self.network_to_client.is_empty() {
            return Ok(());
        }

        let mut chunk = vec![0; MAX_COMPRESSION_CHUNK_LENGTH];
        let length = self.network_to_client.copy_to(&mut chunk);
        chunk.truncate(length);
        self.pending_frame = tunnel_compression::compress_frame(&chunk);
        self.pending_frame_offset = 0;
        self.write_compressed()
    }

    fn push_to_network(&mut self, selector: &mut Selector) {
        while self.push_one_packet_to_network(selector) {
            self.client_to_network.next();
        }
    }

    fn push_one_packet_to_network(&mut self, selector: &mut Selector) -> bool {
        match self.client_to_network.as_ip_packet() {
            Some(ref packet) => {
                let has_pending_frame = self.pending_frame_offset < self.pending_frame.len();
                let mut client_channel = ClientChannel::new(
                    &mut self.network_to_client,
                    &self.stream,
                    self.token,
                    &mut self.interests,
                    has_pending_frame,
                );
                self.router
                    .send_to_network(selector, &mut client_channel, packet);
                true
            }
            None => false,
        }
    }

    fn process_pending(&mut self, selector: &mut Selector) {
        let mut vec = Vec::new();
        mem::swap(&mut self.pending_packet_sources, &mut vec);
        for pending in vec.into_iter() {
            let consumed = {
                let mut source = pending.borrow_mut();
                let result = {
                    let ip_packet = source
                        .get()
                        .expect("Unexpected pending source with no packet");
                    self.send_to_client(selector, &ip_packet)
                };
                #[allow(clippy::match_wild_err_arm)]
                match result {
                    Ok(_) => {
                        source.next(selector);
                        true
                    }
                    Err(ref err) if err.kind() == io::ErrorKind::WouldBlock => false,
                    Err(_) => {
                        panic!("Cannot send packet to client for unknown reason");
                    }
                }
            };
            if !consumed {
                // keep it pending
                self.pending_packet_sources.push(pending);
            }
        }
    }

    pub fn clean_expired_connections(&mut self, selector: &mut Selector) {
        self.router.clean_expired_connections(selector);
    }

    fn must_send_handshake(&self) -> bool {
        self.pending_handshake_offset < self.pending_handshake.len()
    }
}
