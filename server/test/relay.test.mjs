// LimooCast relay test (node --test via `npm test`).
// Spawns the real server on a scratch port, connects one sender + one
// listener with the built-in Node WebSocket client, sends a known PCM
// frame, and asserts the listener receives the exact same bytes.
// Also checks /health and / serve the listener page.
'use strict';
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
const __dirname = path.dirname(fileURLToPath(import.meta.url));

const PORT = 18799;
const BASE = `http://127.0.0.1:${PORT}`;

function waitFor(fn, timeoutMs = 8000) {
  const t0 = Date.now();
  return new Promise((resolve, reject) => {
    (function poll() {
      Promise.resolve().then(fn).then((v) => (v ? resolve(v) : Date.now() - t0 > timeoutMs ? reject(new Error('timeout')) : setTimeout(poll, 100))).catch(() => (Date.now() - t0 > timeoutMs ? reject(new Error('timeout')) : setTimeout(poll, 100)));
    })();
  });
}

test('relay forwards PCM bit-for-bit, sender to listener', async () => {
  const srv = spawn(process.execPath, [path.join(__dirname, '..', 'server.js')], {
    env: { ...process.env, PORT: String(PORT) }, stdio: 'ignore',
  });
  try {
    await waitFor(async () => {
      const r = await fetch(BASE + '/health');
      return r.ok;
    });
    const health = await (await fetch(BASE + '/health')).json();
    assert.equal(health.ok, true);
    assert.equal(health.sampleRate, 48000);
    const page = await (await fetch(BASE + '/')).text();
    assert.match(page, /LimooCast/);
    assert.match(page, /worklet\.js/);

    const room = 'test' + Math.random().toString(36).slice(2, 8);
    const listener = new WebSocket(`ws://127.0.0.1:${PORT}/ws?room=${room}&role=listener`);
    listener.binaryType = 'arraybuffer';
    const received = [];
    const gotHello = new Promise((res) => { listener.onmessage = (ev) => { if (typeof ev.data === 'string') return; received.push(Buffer.from(ev.data)); if (received.length >= 2) res(); }; });
    await new Promise((res, rej) => { listener.onopen = res; listener.onerror = rej; });
    const sender = new WebSocket(`ws://127.0.0.1:${PORT}/ws?room=${room}&role=sender`);
    await new Promise((res, rej) => { sender.onopen = res; sender.onerror = rej; });
    // Two distinct 20 ms PCM frames (3840 bytes each).
    const frameA = Buffer.alloc(3840);
    const frameB = Buffer.alloc(3840);
    for (let i = 0; i < 3840; i += 2) { frameA.writeInt16LE((i * 7) % 20000 - 10000, i); frameB.writeInt16LE(12345 - i, i); }
    sender.send(frameA);
    sender.send(frameB);
    await Promise.race([gotHello, new Promise((_, rej) => setTimeout(() => rej(new Error('no audio relayed')), 5000))]);
    assert.equal(received.length, 2);
    assert.ok(received[0].equals(frameA), 'first frame must arrive bit-for-bit');
    assert.ok(received[1].equals(frameB), 'second frame must arrive bit-for-bit');
    listener.close(); sender.close();
  } finally {
    srv.kill('SIGTERM');
  }
});
