# LimooCast 🍋

Stream the music playing on your **main phone** to your **other phone**,
live and lossless. Main phone keeps full control (play / pause / next in
Spotify or any app). Other phone just opens a link in the browser and
listens — AirPods plugged in there, nothing to install.

## How it works

```
main phone (sender app) --PCM 48kHz stereo, lossless--> relay (Node, :3000)
                                                            |
other phone (browser)  <----------- WebSocket -------------+
```

- **Sender app** (`android/`, Kotlin, no external deps): taps Android's
  `AudioPlaybackCapture` (API 29+) via a MediaProjection consent, captures
  the phone's internal audio mix at 48 kHz stereo 16-bit PCM, and sends
  20 ms frames over WebSocket. No transcoding, no recording, nothing stored.
- **Relay** (`server/`, Node stdlib only, zero npm deps): serves the listener
  page + a minimal RFC 6455 WebSocket server. One sender per room, any number
  of listeners. Bytes are forwarded unchanged — bit-for-bit lossless,
  ~1.5 Mbit/s, ~120 ms jitter buffer on the listener.
- **Listener** (`server/public/index.html` + `worklet.js`): plain web page.
  Tap "Start listening", sound plays through an AudioWorklet ring buffer.
  Volume slider, live level meter, listener count.

## The Spotify catch — read this first

Android only lets an app capture another app's audio if that app allows
it. Spotify's current native player sets `ALLOW_CAPTURE_BY_SYSTEM`, so
**Spotify may stream silence** while YouTube Music, local players and most
other apps work fine. The sender app shows this honestly: a level meter +
a "capturing SILENCE" warning when music is playing but the captured
chunks are all zero. Test with YouTube Music first; if Spotify is silent
on your phone, the fallback is a Spotify-direct mode (listener plays the
same track from Spotify, main phone controls it) — not built in v1.

## Local mode — zero internet data (v1.1.0)

Internet mode streams lossless PCM at ~1.5 Mbit/s, which is **~690 MB
per hour** — uploaded by the sender phone and downloaded by the listener
phone. If both phones are near each other, use local mode instead:

- In the sender app, turn on **Local mode — same Wi-Fi, no internet data**.
- The sender phone runs the listener server itself on port 8899 and
  serves the exact same listener page. It uses **zero internet data** —
  audio never leaves your local network.
- Open the link it shows (e.g. `http://192.168.1.5:8899/`) on the other
  phone and tap Start listening. Same controls, same lossless quality.
- No Wi-Fi? Turn on the main phone's hotspot and connect the other phone
  to it — local mode works over the hotspot too.

Local mode needs both phones on the same network. When they're apart,
switch it off and internet mode (Sweden relay) takes over.

## Deploy the relay (Sweden, via AetherDeploy)

The relay is a plain Node app (`package.json` → `node server.js`,
`PORT` from env, health check `GET /health`). Deploy as an AetherDeploy
project named `limoocast` (Node.js backend, port 3000):

- Live URL: `https://limoocast.s1.awmin.indevs.in:8443`
- Listener link: `https://limoocast.s1.awmin.indevs.in:8443/?room=<code>`
- Sender default server in the app is already that host.

No new firewall port is needed — AetherDeploy/Caddy already terminates
HTTPS on :8443.

## Build the sender APK

Push `android/` to a repo (e.g. `TheLimoo/LimooCast`) — CI
(`android/.github/workflows/build.yml`) builds the debug APK with the
Android SDK + JDK 17 and uploads it as an artifact. No local Android SDK
is needed. CI also runs the relay test (`server/test/relay.test.mjs`).

Local relay test (needs Node 20+):

```
cd server && npm test
```

## Phone test

1. Install the sender APK on the main phone, open it, check the server
   (`limoocast.s1.awmin.indevs.in:8443`) and room code, tap **Copy
   listener link**.
2. Open that link on the other phone, tap **Start listening**.
3. On the main phone tap **Start sharing**, grant audio + screen-capture
   consent, then play music in any app.
4. The meter in the sender app should move; the other phone should play
   the same audio ~0.2 s behind. Pause/next on the main phone — the
   listener follows, because it hears the live mix.
5. Spotify check: if the meter stays flat while Spotify plays loudly,
   Spotify is blocking capture (see above) — try YouTube Music to confirm
   the pipeline works.
