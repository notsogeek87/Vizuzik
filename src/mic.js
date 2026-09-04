// Turns the phone's own microphone into a real-time loudness spectrum, the same shape
// AudioCaptureService produces natively (see AudioCaptureService.java): 32 bands, logarithmically
// spaced between 55 Hz and 7000 Hz, so the visualizer engine (setLevels() in visualizer.js)
// treats mic-driven levels exactly like captured app audio — it has no idea which source fed it.
//
// Unlike the app-audio path, this needs no MediaProjection ("share your screen") consent: just
// the ordinary RECORD_AUDIO runtime permission, which Capacitor's WebView already wires up to
// getUserMedia() on its own (see BridgeWebChromeClient#onPermissionRequest in @capacitor/android).
// That's what makes the microphone safe to use as the default, always-on source, where the
// screen-share dialog is not.

const BAND_COUNT = 32;
const MIN_FREQ = 55;
const MAX_FREQ = 7000;
const FFT_SIZE = 2048;

export class MicCapture {
  constructor() {
    this.stream = null;
    this.audioCtx = null;
    this.analyser = null;
    this.freqData = null;
    this.rafId = null;
    this.running = false;
    this.onLevels = null;
  }

  get supported() {
    const Ctx = window.AudioContext || window.webkitAudioContext;
    return !!(navigator.mediaDevices && navigator.mediaDevices.getUserMedia && Ctx);
  }

  async start() {
    if (this.running) return;
    if (!this.supported) throw new DOMException("AudioContext/getUserMedia unavailable", "NotSupportedError");

    this.stream = await navigator.mediaDevices.getUserMedia({
      audio: { echoCancellation: false, noiseSuppression: false, autoGainControl: false },
    });

    const Ctx = window.AudioContext || window.webkitAudioContext;
    this.audioCtx = new Ctx();
    const source = this.audioCtx.createMediaStreamSource(this.stream);
    this.analyser = this.audioCtx.createAnalyser();
    this.analyser.fftSize = FFT_SIZE;
    this.analyser.smoothingTimeConstant = 0.35;
    // Widened from the AnalyserNode default (-100/-30 dB) so a phone mic picking up music from a
    // room, not a direct line-in, still lights up the bars instead of sitting near-silent.
    this.analyser.minDecibels = -90;
    this.analyser.maxDecibels = -10;
    this.freqData = new Uint8Array(this.analyser.frequencyBinCount);
    source.connect(this.analyser);

    this._bandRanges = this._computeBandRanges();
    this.running = true;
    this._loop();
  }

  stop() {
    this.running = false;
    if (this.rafId != null) {
      cancelAnimationFrame(this.rafId);
      this.rafId = null;
    }
    if (this.stream) {
      for (const track of this.stream.getTracks()) track.stop();
      this.stream = null;
    }
    if (this.audioCtx) {
      this.audioCtx.close().catch(() => {});
      this.audioCtx = null;
    }
    this.analyser = null;
    this.freqData = null;
  }

  /** Precomputes, once per stream, which FFT bins fall into each of the 32 logarithmic bands. */
  _computeBandRanges() {
    const binHz = this.audioCtx.sampleRate / FFT_SIZE;
    const bins = this.freqData.length;
    const ratio = MAX_FREQ / MIN_FREQ;
    const ranges = new Array(BAND_COUNT);
    for (let i = 0; i < BAND_COUNT; i++) {
      const freqLo = MIN_FREQ * Math.pow(ratio, i / (BAND_COUNT - 1));
      const freqHi = MIN_FREQ * Math.pow(ratio, (i + 0.999) / (BAND_COUNT - 1));
      const lo = Math.max(1, Math.min(bins - 1, Math.round(freqLo / binHz)));
      const hi = Math.max(lo + 1, Math.min(bins, Math.round(freqHi / binHz)));
      ranges[i] = [lo, hi];
    }
    return ranges;
  }

  _loop() {
    if (!this.running) return;
    this.rafId = requestAnimationFrame(() => this._loop());
    this.analyser.getByteFrequencyData(this.freqData);
    if (this.onLevels) this.onLevels(this._toBands());
  }

  _toBands() {
    const bands = new Array(BAND_COUNT);
    for (let i = 0; i < BAND_COUNT; i++) {
      const [lo, hi] = this._bandRanges[i];
      let sum = 0;
      for (let b = lo; b < hi; b++) sum += this.freqData[b];
      bands[i] = sum / (hi - lo) / 255;
    }
    return bands;
  }
}
