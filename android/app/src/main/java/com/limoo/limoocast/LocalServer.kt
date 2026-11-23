package com.limoo.limoocast

import android.content.Context
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.Base64
import java.util.Collections

/**
 * Local mode: the sender phone IS the server.
 *
 * A tiny zero-dependency HTTP + WebSocket server running on the phone
 * itself (default port 8899). It serves the exact same listener page that
 * the Sweden relay serves (from the app's assets) and forwards the
 * captured PCM frames straight to browsers on the same Wi-Fi — or to a
 * phone connected to this phone's hotspot. Nothing leaves the local
 * network, so local mode uses zero mobile/internet data.
 *
 * Protocol matches the relay: the page opens ws://<phone-ip>:8899/ws and
 * receives raw 48 kHz stereo PCM16 frames. The capture thread calls
 * [broadcast] with each 20 ms chunk; this class fans it out to every
 * connected listener. Server frames are unmasked (clients mask theirs).
 */
class LocalServer(
    private val context: Context,
    private val port: Int,
) {
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    @Volatile var running: Boolean = false
    private val clients = Collections.synchronizedSet(mutableSetOf<WsConn>())

    val listenerCount: Int get() = clients.size

    fun start() {
        val ss = ServerSocket(port)
        serverSocket = ss
        running = true
        acceptThread = Thread({
            while (running) {
                try {
                    val socket = ss.accept()
                    Thread({ handle(socket) }, "limoocast-local-conn").also { it.isDaemon = true; it.start() }
                } catch (_: Exception) {
                    if (running) Thread.sleep(50)
                }
            }
        }, "limoocast-local-accept").also { it.isDaemon = true; it.start() }
    }

    fun stop() {
        running = false
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        for (c in clients.toList()) c.close()
        clients.clear()
    }

    /** Fan one captured PCM chunk out to every connected listener. */
    fun broadcast(data: ByteArray, len: Int) {
        for (c in clients.toList()) {
            try { c.sendBinary(data, len) } catch (_: Exception) { c.close() }
        }
    }

    private fun handle(socket: Socket) {
        var conn: WsConn? = null
        try {
            socket.soTimeout = 0
            val inp = BufferedInputStream(socket.getInputStream())
            val out = socket.getOutputStream()
            val head = readHeaders(inp) ?: return
            val lines = head.split("\r\n")
            val requestLine = lines.firstOrNull() ?: return
            val headers = mutableMapOf<String, String>()
            for (l in lines.drop(1)) {
                val i = l.indexOf(':')
                if (i > 0) headers[l.substring(0, i).trim().lowercase()] = l.substring(i + 1).trim()
            }
            val path = requestLine.split(" ").getOrNull(1)?.substringBefore("?") ?: "/"
            val upgrade = headers["upgrade"]?.equals("websocket", ignoreCase = true) == true
            val key = headers["sec-websocket-key"]
            if (upgrade && key != null) {
                val accept = Base64.getEncoder().encodeToString(
                    MessageDigest.getInstance("SHA-1")
                        .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray(Charsets.UTF_8))
                )
                out.write(
                    ("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\n\r\n")
                        .toByteArray(Charsets.UTF_8)
                )
                out.flush()
                conn = WsConn(socket, out)
                clients.add(conn)
                conn.readLoop(inp)
                return
            }
            // Plain HTTP: serve the listener page / worklet / health, then close.
            val (body, type) = when (path) {
                "/", "/index.html" -> readAsset("index.html") to "text/html; charset=utf-8"
                "/worklet.js" -> readAsset("worklet.js") to "application/javascript; charset=utf-8"
                "/health" -> """{"ok":true,"service":"limoocast-local","sampleRate":48000,"channels":2,"listeners":${clients.size}}""".toByteArray(Charsets.UTF_8) to "application/json"
                else -> null to null
            }
            if (body == null) {
                val notFound = "not found".toByteArray(Charsets.UTF_8)
                out.write("HTTP/1.1 404 Not Found\r\nContent-Type: text/plain\r\nContent-Length: ${notFound.size}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.UTF_8))
                out.write(notFound)
            } else {
                out.write("HTTP/1.1 200 OK\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\nCache-Control: no-cache\r\nConnection: close\r\n\r\n".toByteArray(Charsets.UTF_8))
                out.write(body)
            }
            out.flush()
        } catch (_: Exception) {
            // client went away — nothing to do
        } finally {
            if (conn != null) clients.remove(conn)
            try { socket.close() } catch (_: Exception) {}
        }
    }

    /** Read bytes until the blank line ending the HTTP headers. */
    private fun readHeaders(inp: BufferedInputStream): String? {
        val buf = ByteArrayOutputStream()
        val tail = ArrayDeque<Int>()
        while (true) {
            val b = inp.read()
            if (b == -1) return null
            buf.write(b)
            tail.addLast(b)
            if (tail.size > 4) tail.removeFirst()
            if (tail.size == 4 && tail.toList() == listOf(13, 10, 13, 10)) break
            if (buf.size() > 8192) return null
        }
        return buf.toString(Charsets.UTF_8.name())
    }

    private fun readAsset(name: String): ByteArray =
        context.assets.open(name).use { it.readBytes() }

    /** One browser listener connection (server side of RFC 6455). */
    private class WsConn(
        private val socket: Socket,
        private val out: OutputStream,
    ) {
        @Volatile private var closed = false

        fun sendBinary(data: ByteArray, len: Int) {
            if (closed) return
            synchronized(out) {
                val header: ByteArray = when {
                    len < 126 -> byteArrayOf(0x82.toByte(), len.toByte())
                    len < 65536 -> byteArrayOf(0x82.toByte(), 126, (len shr 8).toByte(), len.toByte())
                    else -> byteArrayOf(0x82.toByte(), 127, 0, 0, 0, 0, (len shr 24).toByte(), (len shr 16).toByte(), (len shr 8).toByte(), len.toByte())
                }
                out.write(header)
                out.write(data, 0, len)
                out.flush()
            }
        }

        /** Read browser frames until close: answer pings, ignore the rest. */
        fun readLoop(inp: BufferedInputStream) {
            try {
                while (!closed) {
                    val b0 = inp.read(); if (b0 == -1) break
                    val b1 = inp.read(); if (b1 == -1) break
                    val opcode = b0 and 0x0f
                    var len = b1 and 0x7f
                    if (len == 126) {
                        val hi = inp.read(); val lo = inp.read()
                        len = (hi shl 8) or lo
                    } else if (len == 127) {
                        var l = 0L
                        repeat(8) { l = (l shl 8) or inp.read().toLong() }
                        len = l.toInt()
                    }
                    val masked = (b1 and 0x80) != 0
                    val mask = ByteArray(if (masked) 4 else 0)
                    if (masked) { var r = 0; while (r < 4) { val n = inp.read(mask, r, 4 - r); if (n == -1) break; r += n } }
                    val payload = ByteArray(len)
                    var read = 0
                    while (read < len) {
                        val n = inp.read(payload, read, len - read)
                        if (n == -1) break
                        read += n
                    }
                    if (masked) for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i and 3].toInt()).toByte()
                    when (opcode) {
                        9 -> { // ping -> pong
                            synchronized(out) {
                                out.write(byteArrayOf(0x8A.toByte(), payload.size.toByte()))
                                out.write(payload)
                                out.flush()
                            }
                        }
                        8 -> break // close
                        else -> { /* text/binary from listener: not expected, ignore */ }
                    }
                }
            } catch (_: Exception) {
            } finally {
                close()
            }
        }

        fun close() {
            if (closed) return
            closed = true
            try { socket.close() } catch (_: Exception) {}
        }
    }
}
