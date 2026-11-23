// LimooCast playback worklet: receives raw PCM16 stereo frames from the
// main thread and plays them through a small ring buffer. No resampling,
// no compression — the samples are played exactly as captured.
class CastPlayer extends AudioWorkletProcessor {
  constructor() {
    super();
    this.buf = new Float32Array(48000 * 2 * 2); // ~2 s ring buffer
    this.w = 0; this.r = 0; this.underruns = 0;
    this.port.onmessage = (e) => {
      const pcm = e.data; // Int16Array, interleaved stereo
      for (let i = 0; i < pcm.length; i++) {
        this.buf[this.w] = pcm[i] / 32768;
        this.w = (this.w + 1) % this.buf.length;
      }
    };
  }
  process(inputs, outputs) {
    const out = outputs[0];
    const n = out[0].length;
    for (let i = 0; i < n; i++) {
      const avail = (this.w - this.r + this.buf.length) % this.buf.length;
      if (avail < 2) { this.underruns++; out[0][i] = 0; out[1][i] = 0; continue; }
      out[0][i] = this.buf[this.r]; this.r = (this.r + 1) % this.buf.length;
      out[1][i] = this.buf[this.r]; this.r = (this.r + 1) % this.buf.length;
    }
    return true;
  }
}
registerProcessor('cast-player', CastPlayer);
