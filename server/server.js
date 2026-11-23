// LimooCast relay — zero-dependency Node.js (stdlib only).
//
// What it does:
//   * Serves the listener web page (GET /) and a health check (GET /health).
//   * Relays raw PCM audio over WebSocket: one sender per room, any number
//     of listeners. No transcoding, no recording, no storage — bytes that
//     arrive from the sender are forwarded to listeners in the same room
//     unchanged, so the stream is bit-for-bit lossless.
//   * WebSocket is implemented on raw sockets (RFC 6455 handshake + frames)
//     because Node has no built-in WS *server* and this box stays dep-free.
//
// Audio format (fixed, v1): 48 kHz, stereo, 16-bit little-endian PCM.
// One WS binary frame = 20 ms = 960 samples/channel = 3840 bytes.
// At that rate the stream is ~1.536 Mbit/s.
//
// Rooms: ?room=<code> — sender + listeners sharing a code hear each other.
// Anyone with the link can listen; keep the room code unguessable and do
// not share the link publicly. No auth in v1 by design (lightweight).
'use strict';
const http = require('http');
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

const PORT = parseInt(process.env.PORT || '3000', 10);
const PUBLIC_DIR = path.join(__dirname, 'public');
const SAMPLE_RATE = 48000;
const CHANNELS = 2;

function log(...a) { console.log(new Date().toISOString(), ...a); }

// ---------------- rooms ----------------
// roomCode -> { sender: Peer|null, listeners: Set<Peer>, createdAt }
const rooms = new Map();
function getRoom(code) {
  let r = rooms.get(code);
  if (!r) { r = { sender: null, listeners: new Set(), createdAt: Date.now() }; rooms.set(code, r); }
  return r;
}
function roomStats(code) {
  const r = rooms.get(code);
  return { room: code, sender: !!r?.sender, listeners: r ? r.listeners.size : 0 };
}
function broadcastRoom(code) {
  const r = rooms.get(code);
  if (!r) return;
  const msg = JSON.stringify({ type: 'room', sender: !!r.sender, listeners: r.listeners.size });
  for (const l of [...r.listeners]) l.sendText(msg);
  if (r.sender) r.sender.sendText(msg);
}

// ---------------- minimal WebSocket (RFC 6455) ----------------
function wsAccept(key) {
  return crypto.createHash('sha1').update(key + '258EAFA5-E914-47DA-95CA-C5AB0DC85B11').digest('base64');
}

// Parse frames from a buffer. Returns { frames, rest }.
// frames: [{ fin, opcode, payload: Buffer }]. Handles masked client frames,
// 16-bit and 64-bit lengths, ping/pong/close. Fragmented messages are
// reassembled for text; binary frames are delivered per-frame (sender sends
// one frame per 20 ms chunk, so no reassembly is needed in practice).
function parseFrames(buf, frag) {
  const frames = [];
  let off = 0;
  while (true) {
    if (buf.length - off < 2) break;
    const b0 = buf[off], b1 = buf[off + 1];
    const fin = (b0 & 0x80) !== 0;
    const opcode = b0 & 0x0f;
    const masked = (b1 & 0x80) !== 0;
    let len = b1 & 0x7f;
    let p = off + 2;
    if (len === 126) {
      if (buf.length - p < 2) break;
      len = buf.readUInt16BE(p); p += 2;
    } else if (len === 127) {
      if (buf.length - p < 8) break;
      len = Number(buf.readBigUInt64BE(p)); p += 8;
      if (!Number.isSafeInteger(len)) break;
    }
    let mask = null;
    if (masked) {
      if (buf.length - p < 4) break;
      mask = buf.slice(p, p + 4); p += 4;
    }
    if (buf.length - p < len) break;
    let payload = buf.slice(p, p + len);
    if (mask) {
      payload = Buffer.from(payload);
      for (let i = 0; i < payload.length; i++) payload[i] ^= mask[i & 3];
    }
    if (opcode === 0) { // continuation
      if (frag.buf) { frag.buf = Buffer.concat([frag.buf, payload]); if (fin) { frames.push({ fin: true, opcode: frag.opcode, payload: frag.buf }); frag.buf = null; } }
    } else if (!fin && (opcode === 1 || opcode === 2)) {
      frag.buf = Buffer.from(payload); frag.opcode = opcode;
    } else {
      frames.push({ fin, opcode, payload });
    }
    off = p + len;
  }
  return { frames, rest: buf.slice(off) };
}

function encodeFrame(opcode, payload) {
  const len = payload.length;
  let header;
  if (len < 126) { header = Buffer.from([0x80 | opcode, len]); }
  else if (len < 65536) { header = Buffer.alloc(4); header[0] = 0x80 | opcode; header[1] = 126; header.writeUInt16BE(len, 2); }
  else { header = Buffer.alloc(10); header[0] = 0x80 | opcode; header[1] = 127; header.writeBigUInt64BE(BigInt(len), 2); }
  return Buffer.concat([header, payload]);
}

class Peer {
  constructor(socket, room, role) {
    this.socket = socket; this.room = room; this.role = role;
    this.buf = Buffer.alloc(0); this.frag = { buf: null, opcode: 0 };
    this.alive = true; this.bytesIn = 0; this.bytesOut = 0;
    socket.on('data', (d) => this.onData(d));
    socket.on('close', () => this.onClose());
    socket.on('error', () => this.onClose());
  }
  sendText(s) { this.send(1, Buffer.from(s, 'utf8')); }
  sendBinary(b) { this.send(2, b); }
  send(opcode, payload) {
    if (!this.alive) return;
    try { this.socket.write(encodeFrame(opcode, payload)); this.bytesOut += payload.length; } catch { this.onClose(); }
  }
  onData(d) {
    this.buf = Buffer.concat([this.buf, d]);
    const { frames, rest } = parseFrames(this.buf, this.frag);
    this.buf = rest;
    for (const f of frames) this.onFrame(f);
  }
  onFrame(f) {
    if (f.opcode === 9) { this.send(10, f.payload); return; } // ping -> pong
    if (f.opcode === 8) { this.onClose(); return; } // close
    if (f.opcode === 1) { this.onText(f.payload.toString('utf8')); return; }
    if (f.opcode === 2) {
      if (this.role !== 'sender') return;
      this.bytesIn += f.payload.length;
      const r = rooms.get(this.room);
      if (!r) return;
      for (const l of [...r.listeners]) l.sendBinary(f.payload);
      return;
    }
  }
  onText(s) {
    let m; try { m = JSON.parse(s); } catch { return; }
    if (m.type === 'ping') this.sendText(JSON.stringify({ type: 'pong', t: m.t }));
  }
  onClose() {
    if (!this.alive) return;
    this.alive = false;
    const r = rooms.get(this.room);
    if (r) {
      if (r.sender === this) r.sender = null;
      r.listeners.delete(this);
      if (!r.sender && r.listeners.size === 0) rooms.delete(this.room);
      else broadcastRoom(this.room);
    }
    try { this.socket.destroy(); } catch {}
  }
}

function handleUpgrade(req, socket, head) {
  const u = new URL(req.url, 'http://x');
  if (u.pathname !== '/ws') { socket.destroy(); return; }
  const key = req.headers['sec-websocket-key'];
  if (!key) { socket.destroy(); return; }
  const room = (u.searchParams.get('room') || '').replace(/[^A-Za-z0-9_-]/g, '').slice(0, 32) || 'limoo';
  const role = u.searchParams.get('role') === 'sender' ? 'sender' : 'listener';
  const r = getRoom(room);
  if (role === 'sender') {
    if (r.sender && r.sender.alive) { // one sender per room: old sender loses
      try { r.sender.socket.destroy(); } catch {}
      r.sender = null;
    }
  }
  socket.write(
    'HTTP/1.1 101 Switching Protocols\r\n' +
    'Upgrade: websocket\r\nConnection: Upgrade\r\n' +
    'Sec-WebSocket-Accept: ' + wsAccept(key) + '\r\n\r\n'
  );
  const peer = new Peer(socket, room, role);
  if (role === 'sender') r.sender = peer; else r.listeners.add(peer);
  log(`ws ${role} joined room=${room} sender=${!!r.sender} listeners=${r.listeners.size}`);
  peer.sendText(JSON.stringify({ type: 'hello', room, role, sampleRate: SAMPLE_RATE, channels: CHANNELS }));
  broadcastRoom(room);
  if (head && head.length) peer.onData(head);
}

// ---------------- HTTP: listener page + health ----------------
function sendFile(res, file, type) {
  fs.readFile(path.join(PUBLIC_DIR, file), (err, data) => {
    if (err) { res.writeHead(404); res.end('not found'); return; }
    res.writeHead(200, { 'Content-Type': type, 'Cache-Control': 'no-cache' });
    res.end(data);
  });
}

const server = http.createServer((req, res) => {
  const u = new URL(req.url, 'http://x');
  if (u.pathname === '/health') {
    const body = JSON.stringify({
      ok: true, service: 'limoocast', sampleRate: SAMPLE_RATE, channels: CHANNELS,
      rooms: [...rooms.keys()].map(roomStats),
    });
    res.writeHead(200, { 'Content-Type': 'application/json' }); res.end(body); return;
  }
  if (u.pathname === '/' || u.pathname === '/index.html') { sendFile(res, 'index.html', 'text/html; charset=utf-8'); return; }
  if (u.pathname === '/worklet.js') { sendFile(res, 'worklet.js', 'application/javascript; charset=utf-8'); return; }
  res.writeHead(404); res.end('not found');
});
server.on('upgrade', handleUpgrade);

// Prune dead sockets: ping listeners/senders every 30 s (WS ping opcode).
setInterval(() => {
  for (const r of rooms.values()) {
    for (const p of [r.sender, ...r.listeners]) {
      if (p && p.alive) { try { p.socket.write(encodeFrame(9, Buffer.from('hb'))); } catch {} }
    }
  }
}, 30000);

server.listen(PORT, '0.0.0.0', () => log(`LimooCast relay listening on :${PORT} — lossless ${SAMPLE_RATE} Hz stereo PCM`));
