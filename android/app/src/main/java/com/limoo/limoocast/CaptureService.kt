package com.limoo.limoocast

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * Foreground service that captures the phone's internal audio and streams
 * it to the LimooCast relay as lossless 48 kHz stereo PCM.
 *
 * Capture path (Android 10+, API 29):
 *   MediaProjection consent (granted in MainActivity) ->
 *   AudioPlaybackCaptureConfiguration (USAGE_MEDIA + GAME + UNKNOWN) ->
 *   AudioRecord (NO setAudioSource — combining it with the capture config
 *   throws IllegalStateException; the config *is* the source) ->
 *   20 ms PCM chunks -> WebSocket binary frames.
 *
 * Honest limit, shown in the UI: an app can opt out of capture
 * (ALLOW_CAPTURE_BY_SYSTEM / allowAudioPlaybackCapture=false). Spotify's
 * current native player does exactly that, so Spotify may stream silence
 * while other apps work. The service counts all-zero chunks so the UI can
 * tell the user "capturing silence" instead of pretending it works.
 */
class CaptureService : Service() {

    companion object {
        const val ACTION_START = "com.limoo.limoocast.START"
        const val ACTION_STOP = "com.limoo.limoocast.STOP"
        const val EXTRA_SERVER = "server"
        const val EXTRA_ROOM = "room"
        const val CHANNEL_ID = "limoocast"
        const val NOTIF_ID = 1
        const val SAMPLE_RATE = 48000
        // 20 ms at 48 kHz stereo, 16-bit = 960 samples/channel = 3840 bytes
        const val CHUNK_FRAMES = 960
        const val CHUNK_BYTES = CHUNK_FRAMES * 2 * 2
    }

    private var captureThread: Thread? = null
    @Volatile private var stopRequested = false
    private var mediaProjection: MediaProjection? = null
    private var ws: WsClient? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopCapture(); stopSelf(); return START_NOT_STICKY }
            ACTION_START -> {
                val server = intent.getStringExtra(EXTRA_SERVER).orEmpty()
                val room = intent.getStringExtra(EXTRA_ROOM).orEmpty()
                startForeground(NOTIF_ID, notification("LimooCast — streaming to $room"))
                startCapture(server, room)
                return START_STICKY
            }
        }
        return START_NOT_STICKY
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "LimooCast streaming", NotificationManager.IMPORTANCE_LOW)
            ch.description = "Shows while your phone's audio is being shared"
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
    }

    private fun notification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("LimooCast is sharing your audio")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()

    private fun wsUrl(server: String, room: String): String {
        val base = server.trim().trimEnd('/')
        val http = if (base.startsWith("http://") || base.startsWith("https://")) base
            else "https://$base"
        val ws = http.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://")
        return "$ws/ws?room=$room&role=sender"
    }

    private fun startCapture(server: String, room: String) {
        if (captureThread?.isAlive == true) return
        CastStats.error = null
        CastStats.bytesSent = 0
        CastStats.silentChunks = 0
        CastStats.totalChunks = 0
        stopRequested = false
        captureThread = Thread({
            var record: AudioRecord? = null
            try {
                val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                val data = ProjectionHolder.resultData
                    ?: throw IllegalStateException("no MediaProjection consent — grant it in the app first")
                mediaProjection = mgr.getMediaProjection(ProjectionHolder.resultCode, data)
                val projection = mediaProjection
                    ?: throw IllegalStateException("MediaProjection is null")
                projection.registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() { stopCapture() }
                }, null)

                val config = AudioPlaybackCaptureConfiguration.Builder(projection)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .build()
                val format = AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                    .build()
                val minBuf = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT,
                )
                record = AudioRecord.Builder()
                    .setAudioPlaybackCaptureConfig(config)
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(maxOf(minBuf * 4, CHUNK_BYTES * 8))
                    .build()
                if (record.state != AudioRecord.STATE_INITIALIZED) {
                    throw IllegalStateException("AudioRecord failed to initialise")
                }

                val client = WsClient(wsUrl(server, room), onText = { text ->
                    // {"type":"room","sender":true,"listeners":1}
                    val m = Regex("\"listeners\"\\s*:\\s*(\\d+)").find(text)
                    if (m != null) CastStats.listeners = m.groupValues[1].toInt()
                }, onClosed = {
                    CastStats.connected = false
                })
                ws = client
                client.connect()
                CastStats.connected = true

                record.startRecording()
                CastStats.running = true
                val buf = ByteArray(CHUNK_BYTES)
                while (!stopRequested) {
                    val n = record.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    CastStats.totalChunks++
                    // Level + silence detection (peak of this chunk).
                    var peak = 0
                    var allZero = true
                    var i = 0
                    while (i < n) {
                        val s = (buf[i].toInt() and 0xff) or (buf[i + 1].toInt() shl 8)
                        val a = if (s < 0) -s else s
                        if (a > peak) peak = a
                        if (s != 0) allZero = false
                        i += 2
                    }
                    CastStats.level = peak / 32768f
                    if (allZero) CastStats.silentChunks++
                    try {
                        client.sendBinary(buf, 0, n)
                        CastStats.bytesSent += n
                    } catch (e: Exception) {
                        CastStats.error = "send failed: ${e.message}"
                        break
                    }
                }
            } catch (e: Exception) {
                CastStats.error = e.message ?: e.toString()
            } finally {
                try { record?.stop() } catch (_: Exception) {}
                try { record?.release() } catch (_: Exception) {}
                try { ws?.close() } catch (_: Exception) {}
                ws = null
                try { mediaProjection?.stop() } catch (_: Exception) {}
                mediaProjection = null
                CastStats.running = false
                CastStats.connected = false
                stopSelf()
            }
        }, "limoocast-capture").also { it.start() }
    }

    private fun stopCapture() {
        stopRequested = true
        captureThread?.interrupt()
    }

    override fun onDestroy() {
        stopCapture()
        super.onDestroy()
    }
}
