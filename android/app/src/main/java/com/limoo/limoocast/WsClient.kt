package com.limoo.limoocast

import java.io.BufferedInputStream
import java.io.OutputStream
import java.net.Socket
import java.net.URI
import java.util.Base64
import javax.net.ssl.SSLSocketFactory
import kotlin.random.Random

/**
 * Minimal WebSocket client (RFC 6455) with zero dependencies.
 *
 * Supports ws:// and wss://. Sends masked binary frames (as clients must),
 * reads server frames (ping -> pong, text -> onText callback). The sender
 * only needs binary-out + text-in, so fragmentation is handled by
 * accumulating continuation frames for text only.
 */
class WsClient(
    private val url: String,
    private val onText: (String) -> Unit,
    private val onClosed: () -> Unit,
) {
    @Volatile private var socket: Socket? = null
    @Volatile private var out: OutputStream? = null
    @Volatile private var closed = false
    private var readThread: Thread? = null

    fun connect() {
        val uri = URI(url)
        val secure = uri.scheme.equals("wss", ignoreCase = true)
        val host = uri.host ?: throw IllegalArgumentException("no host in $url")
        val port = if (uri.port != -1) uri.port else if (secure) 443 else 80
        val path = (if (uri.rawPath.isNullOrEmpty()) "/" else uri.rawPath) +
            (if (uri.rawQuery != null) "?${uri.rawQuery}" else "")
        val raw: Socket = if (secure) {
            SSLSocketFactory.getDefault().createSocket(host, port)
        } else {
            Socket(host, port)
        }
        raw.soTimeout = 0
        val key = Base64.getEncoder().encodeToString(Random.nextBytes(16))
        val req = "GET $path HTTP/1.1\r\n" +
            "Host: $host:$port\r\n" +
            "Upgrade: websocket\r\n" +
            "Connection: Upgrade\r\n" +
            "Sec-WebSocket-Key: $key\r\n" +
            "Sec-WebSocket-Version: 13\r\n\r\n"
        raw.getOutputStream().write(req.toByteArray(Charsets.UTF_8))
        raw.getOutputStream().flush()
        // Read the 101 response headers (byte-by-byte until \r\n\r\n).
        val inp = BufferedInputStream(raw.getInputStream())
        val header = StringBuilder()
        var prev = 0
        while (true) {
            val b = inp.read()
            if (b == -1) throw IllegalStateException("server closed during handshake")
            header.append(b.toChar())
            if (prev == '\r' && b == '\n'.code) {
                // crude end check: look for blank line
                if (header.endsWith("\r\n\r\n")) break
            }
            // track \r\n\r\n via last 4 chars instead
            if (header.length >= 4 && header.substring(header.length - 4) == "\r\n\r\n") break
            prev = b
            if (header.length > 8192) throw IllegalStateException("handshake headers too large")
        }
        if (!header.contains("101")) throw IllegalStateException("WS handshake failed: ${header.lines().firstOrNull()}")
        socket = raw
        out = raw.getOutputStream()
        readThread = Thread({ readLoop(inp) }, "limoocast-ws-read").also { it.isDaemon = true; it.start() }
    }

    fun sendBinary(data: ByteArray, off: Int = 0, len: Int = data.size) {
        val o = out ?: return
        synchronized(o) {
            val mask = Random.nextBytes(4)
            val header: ByteArray = when {
                len < 126 -> byteArrayOf(0x82.toByte(), (0x80 or len).toByte())
                len < 65536 -> byteArrayOf(0x82.toByte(), (0x80 or 126).toByte(), (len shr 8).toByte(), len.toByte())
                else -> byteArrayOf(
                    0x82.toByte(), (0x80 or 127).toByte(),
                    0, 0, 0, 0,
                    (len shr 24).toByte(), (len shr 16).toByte(), (len shr 8).toByte(), len.toByte(),
                )
            }
            o.write(header)
            o.write(mask)
            val masked = ByteArray(len)
            for (i in 0 until len) masked[i] = (data[off + i].toInt() xor mask[i and 3].toInt()).toByte()
            o.write(masked)
            o.flush()
        }
    }

    private fun readLoop(inp: BufferedInputStream) {
        try {
            var textBuf: ByteArray? = null
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
                val payload = ByteArray(len)
                var read = 0
                while (read < len) {
                    val n = inp.read(payload, read, len - read)
                    if (n == -1) break
                    read += n
                }
                when (opcode) {
                    9 -> sendPong(payload) // ping
                    8 -> break // close
                    1 -> { // text (handle fragmentation minimally)
                        if ((b0 and 0x80) == 0) { textBuf = payload }
                        else {
                            val full = if (textBuf != null) textBuf + payload else payload
                            textBuf = null
                            onText(String(full, Charsets.UTF_8))
                        }
                    }
                    0 -> { // continuation of text
                        if (textBuf != null) {
                            val full = textBuf + payload
                            if ((b0 and 0x80) != 0) { textBuf = null; onText(String(full, Charsets.UTF_8)) }
                            else textBuf = full
                        }
                    }
                    else -> { /* binary from server: not expected */ }
                }
            }
        } catch (_: Exception) {
            // socket closed / error — fall through to onClosed
        } finally {
            close()
        }
    }

    private fun sendPong(payload: ByteArray) {
        val o = out ?: return
        synchronized(o) {
            try {
                o.write(byteArrayOf(0x8A.toByte(), payload.size.toByte()))
                o.write(payload)
                o.flush()
            } catch (_: Exception) {}
        }
    }

    fun close() {
        if (closed) return
        closed = true
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        onClosed()
    }
}
