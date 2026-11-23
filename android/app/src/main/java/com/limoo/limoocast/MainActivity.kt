package com.limoo.limoocast

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.ConnectivityManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.Formatter
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URLEncoder
import kotlin.math.roundToInt

/**
 * LimooCast sender: the main phone.
 *
 * Flow: enter server + room (saved), tap Start sharing -> RECORD_AUDIO
 * permission -> system screen/audio capture consent -> foreground service
 * captures internal audio and streams it. Play music in any app as normal;
 * the other phone just opens the listener link.
 *
 * The level meter is the Spotify test: if music is playing loudly but the
 * meter stays flat and "silent chunks" climbs, the playing app is blocking
 * capture (Spotify's native player does this) — the app says so instead
 * of silently streaming nothing.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var serverInput: EditText
    private lateinit var roomInput: EditText
    private lateinit var localIpInput: EditText
    private lateinit var localSwitch: Switch
    private lateinit var startBtn: Button
    private lateinit var stopBtn: Button
    private lateinit var statusText: TextView
    private lateinit var levelBar: ProgressBar
    private lateinit var statsText: TextView
    private lateinit var linkText: TextView
    private lateinit var copyBtn: Button

    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("limoocast", Context.MODE_PRIVATE) }

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            ProjectionHolder.resultCode = result.resultCode
            ProjectionHolder.resultData = result.data
            startServiceNow()
        } else {
            Toast.makeText(this, "Capture permission denied — sharing can't start", Toast.LENGTH_LONG).show()
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) requestProjection() else {
            Toast.makeText(this, "Microphone/audio permission is required to capture playback", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        serverInput = findViewById(R.id.serverInput)
        roomInput = findViewById(R.id.roomInput)
        localIpInput = findViewById(R.id.localIpInput)
        localSwitch = findViewById(R.id.localSwitch)
        startBtn = findViewById(R.id.startBtn)
        stopBtn = findViewById(R.id.stopBtn)
        statusText = findViewById(R.id.statusText)
        levelBar = findViewById(R.id.levelBar)
        statsText = findViewById(R.id.statsText)
        linkText = findViewById(R.id.linkText)
        copyBtn = findViewById(R.id.copyBtn)

        serverInput.setText(prefs.getString("server", "limoocast.s1.awmin.indevs.in:8443"))
        roomInput.setText(prefs.getString("room", null) ?: randomRoom().also {
            prefs.edit().putString("room", it).apply()
        })
        localSwitch.isChecked = prefs.getBoolean("localMode", false)
        localIpInput.setText(prefs.getString("localIpOverride", ""))
        localIpInput.setOnFocusChangeListener { _, _ ->
            prefs.edit().putString("localIpOverride", localIpInput.text.toString().trim()).apply()
            updateLink()
        }
        localSwitch.setOnCheckedChangeListener { _, _ ->
            prefs.edit().putBoolean("localMode", localSwitch.isChecked).apply()
            serverInput.isEnabled = !localSwitch.isChecked
            roomInput.isEnabled = !localSwitch.isChecked
            updateLink()
        }
        serverInput.isEnabled = !localSwitch.isChecked
        roomInput.isEnabled = !localSwitch.isChecked
        updateLink()

        serverInput.setOnFocusChangeListener { _, _ -> updateLink() }
        roomInput.setOnFocusChangeListener { _, _ -> updateLink() }
        copyBtn.setOnClickListener {
            val link = currentListenerLink()
            if (link == null) {
                Toast.makeText(this, "No link yet — connect this phone to Wi-Fi (or its hotspot), or type its Wi-Fi IP below", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("LimooCast link", link))
            Toast.makeText(this, "Listener link copied — open it on the other phone", Toast.LENGTH_LONG).show()
        }
        startBtn.setOnClickListener {
            saveSettings()
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            } else requestProjection()
        }
        stopBtn.setOnClickListener {
            val i = Intent(this, CaptureService::class.java).setAction(CaptureService.ACTION_STOP)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i) else startService(i)
        }
        handler.post(tick)
    }

    private val tick = object : Runnable {
        override fun run() {
            val running = CastStats.running
            startBtn.isEnabled = !running
            stopBtn.isEnabled = running
            levelBar.progress = (CastStats.level * 100).roundToInt()
            // Re-resolve the local link continuously: joining Wi-Fi (or the
            // hotspot starting) after opening the app must produce a link
            // without reopening it. Cheap — linkText only.
            if (localSwitch.isChecked) updateLink()
            val mb = CastStats.bytesSent / 1048576.0
            statsText.text = if (running && localSwitch.isChecked) {
                "Local · ${"%.2f".format(mb)} MB over Wi-Fi (0 MB internet) · ${CastStats.listeners} listener(s)\n" +
                    "Chunks: ${CastStats.totalChunks} · silent: ${CastStats.silentChunks}"
            } else if (running) {
                "Streaming · ${"%.2f".format(mb)} MB sent · ${CastStats.listeners} listener(s)\n" +
                    "Chunks: ${CastStats.totalChunks} · silent: ${CastStats.silentChunks}"
            } else if (CastStats.error != null) {
                "Stopped — ${CastStats.error}"
            } else {
                "Not streaming"
            }
            statusText.text = when {
                !running && CastStats.error != null -> "Error: ${CastStats.error}"
                running && CastStats.silentChunks > 20 && CastStats.totalChunks > 30 &&
                    CastStats.silentChunks * 10 > CastStats.totalChunks * 9 ->
                    "Capturing SILENCE — the playing app is blocking capture (Spotify does this). Try YouTube Music or a local player."
                running && !CastStats.connected -> "Starting… connecting to relay"
                running -> "● LIVE — sharing your phone's audio"
                else -> "Ready — play music, then tap Start sharing"
            }
            handler.postDelayed(this, 250)
        }
    }

    private fun randomRoom(): String {
        val chars = "abcdefghjkmnpqrstuvwxyz23456789"
        return (1..6).map { chars.random() }.joinToString("")
    }

    private fun saveSettings() {
        prefs.edit()
            .putString("server", serverInput.text.toString().trim())
            .putString("room", roomInput.text.toString().trim())
            .putString("localIpOverride", localIpInput.text.toString().trim())
            .putBoolean("localMode", localSwitch.isChecked)
            .apply()
    }

    /**
     * The listener link, or null when local mode has no usable IP yet.
     * Never returns the "no link yet" message — callers must not copy that.
     */
    private fun currentListenerLink(): String? {
        if (localSwitch.isChecked) {
            val ip = localIp() ?: return null
            return "http://$ip:${CaptureService.LOCAL_PORT}/"
        }
        val server = serverInput.text.toString().trim().trimEnd('/')
        val room = roomInput.text.toString().trim()
        val base = if (server.startsWith("http")) server else "https://$server"
        return "$base/?room=" + URLEncoder.encode(room, "UTF-8")
    }

    private fun updateLink() {
        val link = if (::localSwitch.isInitialized) currentListenerLink() else null
        linkText.text = link ?: "Local mode: connect both phones to the same Wi-Fi (or turn on this phone's hotspot) — no link yet"
    }

    /**
     * This phone's Wi-Fi / hotspot IPv4 address, or null if none is found.
     *
     * v1.1.0 only tried WifiManager (which returns 0.0.0.0 on newer Android
     * without location permission) and interface enumeration — and it only
     * ran once when the screen opened, so joining Wi-Fi afterwards never
     * produced a link. v1.1.1 tries four sources, in order:
     *  1. manual IP typed below (always wins — the guaranteed fallback),
     *  2. ConnectivityManager LinkProperties of the active network,
     *  3. WifiManager connection info,
     *  4. scanning network interfaces (covers hotspot ap0 + wlan0).
     */
    private fun localIp(): String? {
        val override = localIpInput.text.toString().trim()
        if (override.matches(Regex("\\d{1,3}(\\.\\d{1,3}){3}"))) return override
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val props = cm.getLinkProperties(cm.activeNetwork)
            for (addr in props?.linkAddresses ?: emptyList()) {
                val a = addr.address
                if (a is Inet4Address && a.isSiteLocalAddress) return a.hostAddress
            }
        } catch (_: Exception) {}
        try {
            @Suppress("DEPRECATION")
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val wifiIp = Formatter.formatIpAddress(wm.connectionInfo.ipAddress)
            if (wifiIp != "0.0.0.0") return wifiIp
        } catch (_: Exception) {}
        // Fallback: scan interfaces — covers hotspot (ap0) and Wi-Fi (wlan0).
        try {
            val ifs = NetworkInterface.getNetworkInterfaces()?.toList() ?: return null
            val candidates = ifs.filter { it.isUp && !it.isLoopback }
            val preferred = candidates.filter { it.name.startsWith("wlan") || it.name.startsWith("ap") }
            for (iface in (preferred + candidates)) {
                for (addr in iface.inetAddresses) {
                    if (addr is Inet4Address && addr.isSiteLocalAddress) return addr.hostAddress
                }
            }
        } catch (_: Exception) {}
        return null
    }

    private fun requestProjection() {
        val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(mgr.createScreenCaptureIntent())
    }

    private fun startServiceNow() {
        saveSettings()
        val i = Intent(this, CaptureService::class.java).apply {
            action = CaptureService.ACTION_START
            putExtra(CaptureService.EXTRA_SERVER, serverInput.text.toString().trim())
            putExtra(CaptureService.EXTRA_ROOM, roomInput.text.toString().trim())
            putExtra(
                CaptureService.EXTRA_MODE,
                if (localSwitch.isChecked) CaptureService.MODE_LOCAL else CaptureService.MODE_INTERNET,
            )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i) else startService(i)
        Toast.makeText(this, "Sharing started — now play music in any app", Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        super.onDestroy()
    }
}
