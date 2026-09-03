// Full-screen music visualizer. When AudioCaptureService is capturing Deezer's real output
// (Android 10+, user-granted), every scene is driven by the actual spectrum via setLevels();
// otherwise a synthetic groove keeps the screen alive so it never looks frozen.
//
// One analysis pass feeds five scenes:
//   cover   - a restrained halo around the artwork (the art stays the hero)
//   bars    - a mirrored spectrum stage, bass at the centre, treble at the edges
//   radial  - a reactive corona ringing the spinning disc
//   aurora  - flowing light ribbons, one per frequency slice
//   nebula  - an orbiting particle galaxy with motion trails
//
// Everything is drawn additively and then bloomed by blitting the frame back over itself
// through a blur, which is what gives the neon "lit from within" look at almost no cost.

export const VISUAL_STYLES = ["cover", "bars", "radial", "aurora", "nebula"];

// Matches AudioCaptureService's BAND_COUNT on the native side so live levels map 1:1 with
// no interpolation needed.
const BAR_COUNT = 32;
const LIVE_LEVELS_TIMEOUT_MS = 500;
const BEAT_HISTORY = 48;
const MIRROR_SLOTS = 64;

const reduceMotion =
  typeof matchMedia === "function" && matchMedia("(prefers-reduced-motion: reduce)").matches;

function clamp01(value) {
  return value < 0 ? 0 : value > 1 ? 1 : value;
}

function lerp(a, b, t) {
  return a + (b - a) * t;
}

export class Visualizer {
  constructor(canvas) {
    this.canvas = canvas;
    this.ctx = canvas.getContext("2d");
    this.running = false;
    this.rafId = null;
    this.isPlaying = false;
    this.style = "cover";
    this.width = 0;
    this.height = 0;
    this.quality = reduceMotion ? 0.6 : 1;

    // Palette, in rgb triplets, kept as a smoothly-interpolated copy so a track change melts
    // from one colour scheme into the next instead of snapping.
    this.palette = [
      [124, 92, 255],
      [236, 72, 153],
      [56, 189, 248],
    ];
    this.livePalette = this.palette.map((c) => c.slice());
    this.hue = 258;
    this.targetHue = 258;
    this.saturation = 72;

    this.bars = new Array(BAR_COUNT).fill(0);
    this.peaks = new Array(BAR_COUNT).fill(0);
    this.peakVel = new Array(BAR_COUNT).fill(0);

    this.bass = 0;
    this.mid = 0;
    this.treble = 0;
    this.energy = 0;
    this.avgLevel = 0;
    this.beatEnergy = 0;
    this.beatCount = 0;
    this.bassHistory = new Array(BEAT_HISTORY).fill(0);
    this.bassCursor = 0;
    this.lastBeatAt = 0;

    this.ripples = [];
    this.particles = [];
    this.orbiters = [];
    this.time = 0;
    this.spin = 0;

    this.nextSyntheticBeatAt = 0;
    this.startTime = 0;

    this.liveLevels = null;
    this.lastLiveAt = 0;
    this.liveSilenceSince = null;

    // Where the artwork sits on screen: the corona, halo and galaxy core all orbit this point
    // so the canvas and the DOM disc read as one object rather than two overlapping layers.
    this.focusElement = null;
    this.focus = { x: 0, y: 0, r: 120 };

    this.onFrame = null;

    // Offscreen buffers: `glow` is the half-resolution bloom pass, `trail` holds nebula's
    // motion trails (kept apart from the main canvas so bloom can't feed back into itself).
    this.glowCanvas = document.createElement("canvas");
    this.glowCtx = this.glowCanvas.getContext("2d");
    this.trailCanvas = document.createElement("canvas");
    this.trailCtx = this.trailCanvas.getContext("2d");
    this.bloomSupported = supportsFilter(this.ctx);

    this._onResize = () => this.resize();
    window.addEventListener("resize", this._onResize);
    window.addEventListener("orientationchange", this._onResize);
  }

  /* ---------------------------------------------------------------- lifecycle */

  start() {
    this.resize();
    if (this.running) return;
    this.running = true;
    this.startTime = performance.now();
    this.nextSyntheticBeatAt = this.startTime + 350;
    this.lastFrameAt = this.startTime;
    this._loop();
  }

  stop() {
    this.running = false;
    if (this.rafId != null) {
      cancelAnimationFrame(this.rafId);
      this.rafId = null;
    }
  }

  /** Wipes every buffer: stopping alone would leave the last frame frozen on screen. */
  clear() {
    if (!this.width) return;
    this.ctx.clearRect(0, 0, this.width, this.height);
    this.trailCtx.clearRect(0, 0, this.width, this.height);
    this.particles.length = 0;
    this.ripples.length = 0;
    for (const o of this.orbiters) {
      o.px = null;
      o.py = null;
    }
  }

  destroy() {
    this.stop();
    window.removeEventListener("resize", this._onResize);
    window.removeEventListener("orientationchange", this._onResize);
  }

  setPlaying(isPlaying) {
    this.isPlaying = isPlaying;
  }

  setStyle(style) {
    if (!VISUAL_STYLES.includes(style) || style === this.style) return;
    this.style = style;
    this.particles.length = 0;
    this.ripples.length = 0;
    if (this.trailCtx && this.width) {
      this.trailCtx.clearRect(0, 0, this.width, this.height);
    }
    this._seedOrbiters();
  }

  /** Feeds a real-time loudness spectrum (0..1 per band) from AudioCaptureService. */
  setLevels(levels) {
    if (!levels || !levels.length) return;
    this.liveLevels = levels;
    this.lastLiveAt = performance.now();
  }

  /** Recolours every scene from the album art palette (see palette.js). */
  setPalette(palette) {
    if (!palette || !palette.colors) return;
    this.palette = palette.colors;
    this.targetHue = palette.hue;
    this.saturation = palette.saturation;
    // A shockwave on track change, so a new song visibly *lands* instead of just appearing.
    this._emitRipple(1.1);
  }

  /** The element the canvas should treat as the centre of the composition (the disc). */
  setFocusElement(element) {
    this.focusElement = element;
    this.updateFocus();
  }

  updateFocus() {
    if (!this.focusElement) {
      this.focus = { x: this.width / 2, y: this.height / 2, r: Math.min(this.width, this.height) * 0.22 };
      return;
    }
    const rect = this.focusElement.getBoundingClientRect();
    if (!rect.width) return;
    this.focus = {
      x: rect.left + rect.width / 2,
      y: rect.top + rect.height / 2,
      r: Math.max(rect.width, rect.height) / 2,
    };
  }

  /**
   * Answers "is this actually reacting to Deezer's audio right now?" for the UI badge.
   * - "simulated": no live data (unsupported device, capture denied, or the stream stalled).
   * - "silent": live events ARE arriving, but every band has stayed near-zero for a while —
   *   typically means Deezer opted out of playback capture (Android silently mutes it) rather
   *   than a bug, since real playback almost never sits perfectly flat for seconds.
   * - "live": receiving real, non-flat levels.
   */
  get captureStatus() {
    const now = performance.now();
    const hasLive = this.isPlaying && this.liveLevels && now - this.lastLiveAt < LIVE_LEVELS_TIMEOUT_MS;
    if (!hasLive) return "simulated";
    if (this.liveSilenceSince != null && now - this.liveSilenceSince > 3000) return "silent";
    return "live";
  }

  resize() {
    const rect = this.canvas.getBoundingClientRect();
    if (rect.width === 0 || rect.height === 0) return;
    // Capped device pixel ratio: a 3x buffer on a phone screen costs a lot of fill rate and
    // buys nothing visible once everything is glowing and blurred anyway.
    const dpr = Math.min(window.devicePixelRatio || 1, 2) * this.quality;
    this.width = rect.width;
    this.height = rect.height;
    this.canvas.width = Math.round(rect.width * dpr);
    this.canvas.height = Math.round(rect.height * dpr);
    this.ctx.setTransform(dpr, 0, 0, dpr, 0, 0);

    const glowScale = dpr * 0.5;
    this.glowCanvas.width = Math.max(1, Math.round(rect.width * glowScale));
    this.glowCanvas.height = Math.max(1, Math.round(rect.height * glowScale));

    this.trailCanvas.width = this.canvas.width;
    this.trailCanvas.height = this.canvas.height;
    this.trailCtx.setTransform(dpr, 0, 0, dpr, 0, 0);

    this._seedOrbiters();
    this.updateFocus();
  }

  /* ---------------------------------------------------------------- analysis */

  _loop() {
    if (!this.running) return;
    this.rafId = requestAnimationFrame(() => this._loop());
    const now = performance.now();
    // Clamped delta: after the app is backgrounded, one huge frame would otherwise fling every
    // particle off-screen the moment it comes back.
    const dt = Math.min(0.05, (now - this.lastFrameAt) / 1000) || 0.016;
    this.lastFrameAt = now;
    this._update(now, dt);
    this._draw(dt);
    if (this.onFrame) {
      this.onFrame({
        beat: this.beatEnergy,
        level: this.energy,
        bass: this.bass,
        treble: this.treble,
      });
    }
  }

  _update(now, dt) {
    const hasLive = this.isPlaying && this.liveLevels && now - this.lastLiveAt < LIVE_LEVELS_TIMEOUT_MS;
    if (hasLive) this._updateFromLiveLevels();
    else this._updateSynthetic(now);

    this._updateBands();
    this._detectBeat(now);
    this._updatePeaks(dt);

    this.time += dt * (this.isPlaying ? 1 : 0.25);
    this.spin += dt * (0.12 + this.energy * 0.5 + this.beatEnergy * 0.4);
    this.hue += (this.targetHue - this.hue) * 0.03;
    for (let i = 0; i < this.livePalette.length; i++) {
      const target = this.palette[i] || this.palette[0];
      for (let c = 0; c < 3; c++) {
        this.livePalette[i][c] = lerp(this.livePalette[i][c], target[c], 0.05);
      }
    }

    this._updateRipples(dt);
    this._updateParticles(dt);
  }

  _updateFromLiveLevels() {
    for (let i = 0; i < BAR_COUNT; i++) {
      const target = clamp01(this.liveLevels[i] || 0);
      // Asymmetric smoothing: snap up on transients, glide back down. Symmetric smoothing is
      // what makes most visualizers feel mushy and a beat late.
      const k = target > this.bars[i] ? 0.62 : 0.14;
      this.bars[i] += (target - this.bars[i]) * k;
    }
  }

  _updateSynthetic(now) {
    if (!this.isPlaying) {
      for (let i = 0; i < BAR_COUNT; i++) this.bars[i] += (0 - this.bars[i]) * 0.06;
      return;
    }
    const t = (now - this.startTime) / 1000;
    if (now >= this.nextSyntheticBeatAt) {
      const bpm = 96 + Math.random() * 44;
      this.nextSyntheticBeatAt = now + 60000 / bpm;
      this.syntheticKick = 1;
    }
    this.syntheticKick = (this.syntheticKick || 0) * 0.9;

    for (let i = 0; i < BAR_COUNT; i++) {
      const norm = i / (BAR_COUNT - 1);
      // Pink-ish spectral tilt (loud lows, quieter highs) so the fake spectrum has the same
      // silhouette as real music instead of a flat wall of bars.
      const tilt = Math.pow(1 - norm, 1.35) * 0.75 + 0.2;
      const slow = 0.5 + 0.5 * Math.sin(t * (0.6 + norm * 1.4) + i * 0.42);
      const fast = 0.5 + 0.5 * Math.sin(t * (2.4 + norm * 3.1) + i * 0.87);
      const kick = this.syntheticKick * (1 - norm * 0.55);
      const value = clamp01((slow * 0.55 + fast * 0.3) * tilt + kick * 0.6);
      this.bars[i] += (value - this.bars[i]) * (value > this.bars[i] ? 0.5 : 0.16);
    }
  }

  _updateBands() {
    let sum = 0;
    let bass = 0;
    let mid = 0;
    let treble = 0;
    for (let i = 0; i < BAR_COUNT; i++) {
      const v = this.bars[i];
      sum += v;
      if (i < 6) bass += v;
      else if (i < 18) mid += v;
      else treble += v;
    }
    this.avgLevel = sum / BAR_COUNT;
    this.bass = bass / 6;
    this.mid = mid / 12;
    this.treble = treble / 14;
    this.energy += (this.avgLevel - this.energy) * 0.12;

    if (this.avgLevel < 0.03) {
      if (this.liveSilenceSince == null) this.liveSilenceSince = performance.now();
    } else {
      this.liveSilenceSince = null;
    }
  }

  /**
   * Adaptive beat detection: a beat is bass energy standing clearly above its own recent
   * running average, not above a fixed threshold — so quiet ballads and loud club tracks both
   * pulse, instead of one flatlining and the other strobing.
   */
  _detectBeat(now) {
    this.beatEnergy *= 0.9;
    this.bassHistory[this.bassCursor] = this.bass;
    this.bassCursor = (this.bassCursor + 1) % BEAT_HISTORY;

    let avg = 0;
    for (let i = 0; i < BEAT_HISTORY; i++) avg += this.bassHistory[i];
    avg /= BEAT_HISTORY;

    const isPeak = this.bass > avg * 1.32 + 0.015 && this.bass > 0.06;
    if (isPeak && now - this.lastBeatAt > 190) {
      this.lastBeatAt = now;
      this.beatCount++;
      this.beatEnergy = 1;
      this._emitRipple(0.55 + clamp01(this.bass) * 0.6);
      this._burstParticles();
    }
  }

  _updatePeaks(dt) {
    for (let i = 0; i < BAR_COUNT; i++) {
      if (this.bars[i] >= this.peaks[i]) {
        this.peaks[i] = this.bars[i];
        this.peakVel[i] = 0;
      } else {
        this.peakVel[i] += dt * 0.9;
        this.peaks[i] = Math.max(this.bars[i], this.peaks[i] - this.peakVel[i] * dt * 2.2);
      }
    }
  }

  /* ---------------------------------------------------------------- effects state */

  _emitRipple(strength) {
    if (this.ripples.length > 8) return;
    this.ripples.push({ life: 1, strength, color: this._colorIndexForBeat() });
  }

  _colorIndexForBeat() {
    return this.beatCount % this.livePalette.length;
  }

  _seedOrbiters() {
    const count = Math.round((this.style === "nebula" ? 130 : 46) * this.quality);
    this.orbiters = [];
    for (let i = 0; i < count; i++) {
      this.orbiters.push({
        band: Math.floor(Math.random() * BAR_COUNT),
        angle: Math.random() * Math.PI * 2,
        speed: 0.08 + Math.random() * 0.5,
        radius: 0.18 + Math.random() * 0.85,
        size: 0.6 + Math.random() * 2.4,
        color: Math.floor(Math.random() * 3),
        twinkle: Math.random() * Math.PI * 2,
        px: null,
        py: null,
        rad: null,
      });
    }
  }

  _burstParticles() {
    if (this.style === "cover" || this.style === "aurora") return;
    const count = Math.round((this.style === "nebula" ? 10 : 6) * this.quality);
    const max = this.style === "nebula" ? 220 : 120;
    for (let i = 0; i < count && this.particles.length < max; i++) {
      const angle = Math.random() * Math.PI * 2;
      const speed = 40 + Math.random() * 190 * (0.4 + this.bass);
      this.particles.push({
        x: this.focus.x,
        y: this.focus.y,
        vx: Math.cos(angle) * speed,
        vy: Math.sin(angle) * speed,
        life: 1,
        decay: 0.4 + Math.random() * 0.5,
        size: 1 + Math.random() * 2.6,
        color: Math.floor(Math.random() * 3),
      });
    }
  }

  _updateRipples(dt) {
    for (const ripple of this.ripples) ripple.life -= dt * 0.85;
    this.ripples = this.ripples.filter((r) => r.life > 0);
  }

  _updateParticles(dt) {
    for (const p of this.particles) {
      p.x += p.vx * dt;
      p.y += p.vy * dt;
      p.vx *= 0.975;
      p.vy = p.vy * 0.975 - 12 * dt; // gentle updraft: embers rise as they fade
      p.life -= dt * p.decay;
    }
    this.particles = this.particles.filter((p) => p.life > 0);
  }

  /* ---------------------------------------------------------------- rendering */

  _draw(dt) {
    const { ctx, width, height } = this;
    if (!width || !height) return;
    ctx.clearRect(0, 0, width, height);
    ctx.save();
    ctx.globalCompositeOperation = "lighter";
    switch (this.style) {
      case "bars":
        this._drawBars(ctx);
        break;
      case "radial":
        this._drawRadial(ctx);
        break;
      case "aurora":
        this._drawAurora(ctx);
        break;
      case "nebula":
        this._drawNebula(ctx, dt);
        break;
      default:
        this._drawCoverHalo(ctx);
    }
    this._drawParticles(ctx);
    ctx.restore();
    this._applyBloom();
  }

  /**
   * Bloom in two blits: shrink the finished frame into the half-size buffer, then paint it
   * back over itself through a blur in "lighter" mode. Far cheaper than per-shape shadowBlur,
   * and it makes bright overlaps burn out to white the way real light does.
   */
  _applyBloom() {
    if (!this.bloomSupported || this.quality < 0.7) return;
    const { ctx, width, height, glowCtx, glowCanvas } = this;
    glowCtx.globalCompositeOperation = "copy";
    glowCtx.drawImage(this.canvas, 0, 0, glowCanvas.width, glowCanvas.height);
    ctx.save();
    ctx.globalCompositeOperation = "lighter";
    ctx.globalAlpha = 0.55 + this.beatEnergy * 0.2;
    ctx.filter = `blur(${6 + this.energy * 8}px)`;
    ctx.drawImage(glowCanvas, 0, 0, width, height);
    ctx.restore();
  }

  /** rgba() from the (smoothly interpolated) album palette. */
  _rgba(index, alpha) {
    const c = this.livePalette[index % this.livePalette.length];
    return `rgba(${c[0] | 0}, ${c[1] | 0}, ${c[2] | 0}, ${alpha})`;
  }

  /**
   * Maps a mirrored slot (0..MIRROR_SLOTS-1) onto a spectrum band so bass lands in the middle
   * and treble at both ends. The symmetry is what turns a raw spectrum into something that
   * reads as designed rather than as a readout.
   */
  _mirroredBand(slot) {
    const d = Math.abs(slot - (MIRROR_SLOTS - 1) / 2) / ((MIRROR_SLOTS - 1) / 2);
    return this.bars[Math.min(BAR_COUNT - 1, Math.round(d * (BAR_COUNT - 1)))];
  }

  _mirroredPeak(slot) {
    const d = Math.abs(slot - (MIRROR_SLOTS - 1) / 2) / ((MIRROR_SLOTS - 1) / 2);
    return this.peaks[Math.min(BAR_COUNT - 1, Math.round(d * (BAR_COUNT - 1)))];
  }

  /* --- cover: a halo that frames the artwork without competing with it --- */

  _drawCoverHalo(ctx) {
    const { x, y, r } = this.focus;
    const pulse = 1 + this.beatEnergy * 0.06 + this.energy * 0.04;

    const halo = ctx.createRadialGradient(x, y, r * 0.9, x, y, r * 2.5 * pulse);
    halo.addColorStop(0, this._rgba(0, 0.34 + this.beatEnergy * 0.22));
    halo.addColorStop(0.45, this._rgba(1, 0.14));
    halo.addColorStop(1, this._rgba(2, 0));
    ctx.fillStyle = halo;
    ctx.beginPath();
    ctx.arc(x, y, r * 2.5 * pulse, 0, Math.PI * 2);
    ctx.fill();

    // Spectrum spokes hugging the artwork's edge — present enough to feel alive, faint enough
    // that the cover still reads first.
    const spokes = 96;
    ctx.lineCap = "round";
    for (let i = 0; i < spokes; i++) {
      const angle = (i / spokes) * Math.PI * 2 + this.spin * 0.25;
      const level = this._mirroredBand(Math.round((i / spokes) * (MIRROR_SLOTS - 1)));
      const inner = r * 1.05;
      const outer = inner + level * r * 0.42 + this.beatEnergy * r * 0.05;
      ctx.strokeStyle = this._rgba(i % 3, 0.1 + level * 0.5);
      ctx.lineWidth = 2;
      ctx.beginPath();
      ctx.moveTo(x + Math.cos(angle) * inner, y + Math.sin(angle) * inner);
      ctx.lineTo(x + Math.cos(angle) * outer, y + Math.sin(angle) * outer);
      ctx.stroke();
    }

    this._drawRipples(ctx, x, y, r * 1.05, r * 2.6);
    this._drawFloatingMotes(ctx, 0.35);
  }

  /* --- bars: a mirrored spectrum stage --- */

  _drawBars(ctx) {
    const { width, height } = this;
    const baseline = height * 0.8;
    const maxHeight = height * 0.52;
    const slotWidth = width / MIRROR_SLOTS;
    const barWidth = slotWidth * 0.62;
    const radius = barWidth / 2;

    this._drawFloatingMotes(ctx, 0.5);

    for (let i = 0; i < MIRROR_SLOTS; i++) {
      const level = this._mirroredBand(i);
      const h = Math.max(barWidth * 0.6, level * maxHeight);
      const x = i * slotWidth + (slotWidth - barWidth) / 2;
      const colorIndex = i / MIRROR_SLOTS < 0.5 ? (i % 2 ? 0 : 1) : (i % 2 ? 2 : 0);

      const grad = ctx.createLinearGradient(0, baseline - h, 0, baseline);
      grad.addColorStop(0, this._rgba(colorIndex, 0.95));
      grad.addColorStop(0.6, this._rgba(colorIndex, 0.55));
      grad.addColorStop(1, this._rgba(colorIndex, 0.12));
      ctx.fillStyle = grad;
      roundedRect(ctx, x, baseline - h, barWidth, h, radius);
      ctx.fill();

      // Reflection below the stage line: sells the "polished floor" depth cue.
      const mirror = ctx.createLinearGradient(0, baseline, 0, baseline + h * 0.5);
      mirror.addColorStop(0, this._rgba(colorIndex, 0.28));
      mirror.addColorStop(1, this._rgba(colorIndex, 0));
      ctx.fillStyle = mirror;
      roundedRect(ctx, x, baseline + 3, barWidth, h * 0.5, radius);
      ctx.fill();

      // Peak cap floating on top with gravity — the classic detail that makes a spectrum
      // readable, showing where each band just was.
      const peakY = baseline - Math.max(h, this._mirroredPeak(i) * maxHeight) - 5;
      ctx.fillStyle = this._rgba(colorIndex, 0.85);
      roundedRect(ctx, x, peakY, barWidth, 3, 1.5);
      ctx.fill();
    }

    // Horizon line: a thin blade of light along the stage, brightening on every beat.
    const horizon = ctx.createLinearGradient(0, baseline - 2, width, baseline + 2);
    horizon.addColorStop(0, this._rgba(0, 0));
    horizon.addColorStop(0.5, this._rgba(1, 0.5 + this.beatEnergy * 0.5));
    horizon.addColorStop(1, this._rgba(2, 0));
    ctx.fillStyle = horizon;
    ctx.fillRect(0, baseline - 1, width, 2);
  }

  /* --- radial: a corona around the disc --- */

  _drawRadial(ctx) {
    const { x, y } = this.focus;
    const r = this.focus.r;
    const spokes = 128;
    const inner = r * 1.12;

    // Closed organic blob first, so the spikes read as rays leaving a body of light.
    ctx.beginPath();
    for (let i = 0; i <= spokes; i++) {
      const t = i / spokes;
      const angle = t * Math.PI * 2 + this.spin * 0.35;
      const level = this._mirroredBand(Math.round(t * (MIRROR_SLOTS - 1)));
      const rad = inner * (1 + level * 0.55 + this.beatEnergy * 0.08);
      const px = x + Math.cos(angle) * rad;
      const py = y + Math.sin(angle) * rad;
      if (i === 0) ctx.moveTo(px, py);
      else ctx.lineTo(px, py);
    }
    ctx.closePath();
    const blob = ctx.createRadialGradient(x, y, inner * 0.8, x, y, inner * 1.9);
    blob.addColorStop(0, this._rgba(0, 0.42));
    blob.addColorStop(0.55, this._rgba(1, 0.2));
    blob.addColorStop(1, this._rgba(2, 0));
    ctx.fillStyle = blob;
    ctx.fill();

    ctx.lineCap = "round";
    for (let i = 0; i < spokes; i++) {
      const t = i / spokes;
      const angle = t * Math.PI * 2 - this.spin * 0.5;
      const level = this._mirroredBand(Math.round(t * (MIRROR_SLOTS - 1)));
      const start = inner * 1.02;
      const end = start + level * r * 1.25 + this.beatEnergy * r * 0.12;
      ctx.strokeStyle = this._rgba(i % 3, 0.12 + level * 0.75);
      ctx.lineWidth = 1.5 + level * 3;
      ctx.beginPath();
      ctx.moveTo(x + Math.cos(angle) * start, y + Math.sin(angle) * start);
      ctx.lineTo(x + Math.cos(angle) * end, y + Math.sin(angle) * end);
      ctx.stroke();
    }

    // Orbiting sparks, each pinned to one band, so the ring keeps a sense of depth.
    for (const o of this.orbiters) {
      const level = this.bars[o.band];
      const angle = o.angle + this.spin * o.speed;
      const rad = inner * (1.25 + o.radius * 0.9) + level * r * 0.5;
      const px = x + Math.cos(angle) * rad;
      const py = y + Math.sin(angle) * rad * 0.92;
      const alpha = 0.2 + level * 0.7;
      ctx.fillStyle = this._rgba(o.color, alpha);
      ctx.beginPath();
      ctx.arc(px, py, o.size * (0.7 + level), 0, Math.PI * 2);
      ctx.fill();
    }

    this._drawRipples(ctx, x, y, inner, Math.max(this.width, this.height) * 0.75);
  }

  /* --- aurora: ribbons of light, one per frequency slice --- */

  _drawAurora(ctx) {
    const { width, height } = this;
    const ribbons = reduceMotion ? 4 : 6;
    const step = Math.max(12, width / 48);

    for (let r = 0; r < ribbons; r++) {
      const from = Math.floor((r / ribbons) * BAR_COUNT);
      const to = Math.floor(((r + 1) / ribbons) * BAR_COUNT);
      let amp = 0;
      for (let i = from; i < to; i++) amp += this.bars[i];
      amp /= Math.max(1, to - from);

      const phase = this.time * (0.35 + r * 0.11);
      const baseY = height * (0.22 + (r / (ribbons - 1)) * 0.56);
      const swing = height * 0.035 + amp * height * 0.2 + this.beatEnergy * height * 0.02;

      ctx.beginPath();
      for (let x = -step; x <= width + step; x += step) {
        const n =
          Math.sin(x * 0.0052 + phase) * 0.62 +
          Math.sin(x * 0.0131 - phase * 1.4 + r) * 0.28 +
          Math.sin(x * 0.0271 + phase * 0.7) * 0.1;
        const y = baseY + n * swing;
        if (x <= -step) ctx.moveTo(x, y);
        else ctx.lineTo(x, y);
      }

      const grad = ctx.createLinearGradient(0, 0, width, 0);
      grad.addColorStop(0, this._rgba(r % 3, 0));
      grad.addColorStop(0.25, this._rgba(r % 3, 0.34 + amp * 0.5));
      grad.addColorStop(0.6, this._rgba((r + 1) % 3, 0.28 + amp * 0.55));
      grad.addColorStop(1, this._rgba((r + 2) % 3, 0));
      ctx.strokeStyle = grad;
      ctx.lineWidth = 2 + amp * 46 + this.beatEnergy * 6;
      ctx.lineJoin = "round";
      ctx.lineCap = "round";
      ctx.stroke();

      // A crisp filament riding the same curve keeps the soft band from turning into fog.
      ctx.strokeStyle = this._rgba((r + 1) % 3, 0.35 + amp * 0.4);
      ctx.lineWidth = 1.2;
      ctx.stroke();
    }

    this._drawFloatingMotes(ctx, 0.8);
  }

  /* --- nebula: an orbiting galaxy with motion trails --- */

  _drawNebula(ctx, dt) {
    const { trailCtx, width, height } = this;
    const { x, y } = this.focus;
    const maxR = Math.max(width, height) * 0.55;

    // Trails live on their own buffer: fading the main canvas instead would let the bloom
    // pass feed back into itself and wash the whole screen out within a second.
    trailCtx.globalCompositeOperation = "destination-out";
    trailCtx.fillStyle = `rgba(0, 0, 0, ${Math.min(0.5, 3.2 * dt)})`;
    trailCtx.fillRect(0, 0, width, height);
    trailCtx.globalCompositeOperation = "lighter";

    trailCtx.lineCap = "round";
    for (const o of this.orbiters) {
      const level = this.bars[o.band];
      o.angle += dt * o.speed * (0.5 + level * 3.2 + this.beatEnergy * 1.4);
      // Each orbiter eases toward its band's radius rather than snapping to it: without this
      // the trail zig-zags every frame instead of sweeping a smooth arc.
      const targetRad = maxR * o.radius * (0.42 + level * 0.5) + this.beatEnergy * 26;
      o.rad = o.rad == null ? targetRad : o.rad + (targetRad - o.rad) * 0.07;
      const rad = o.rad;
      const px = x + Math.cos(o.angle) * rad;
      const py = y + Math.sin(o.angle) * rad * 0.66; // squashed orbit = a galaxy seen at an angle
      const twinkle = 0.55 + 0.45 * Math.sin(this.time * 3 + o.twinkle);
      const alpha = (0.15 + level * 0.8) * twinkle;
      const size = o.size * (0.6 + level * 1.6);

      // Stroke from the previous position instead of stamping a dot: at 60fps a dot per frame
      // reads as a dotted line, a segment reads as a streak of light.
      if (o.px != null) {
        trailCtx.strokeStyle = this._rgba(o.color, alpha);
        trailCtx.lineWidth = size;
        trailCtx.beginPath();
        trailCtx.moveTo(o.px, o.py);
        trailCtx.lineTo(px, py);
        trailCtx.stroke();
      } else {
        trailCtx.fillStyle = this._rgba(o.color, alpha);
        trailCtx.beginPath();
        trailCtx.arc(px, py, size, 0, Math.PI * 2);
        trailCtx.fill();
      }
      o.px = px;
      o.py = py;
    }

    ctx.drawImage(this.trailCanvas, 0, 0, width, height);

    const core = ctx.createRadialGradient(x, y, 0, x, y, this.focus.r * (1.6 + this.beatEnergy * 0.5));
    core.addColorStop(0, this._rgba(0, 0.55 + this.beatEnergy * 0.3));
    core.addColorStop(0.5, this._rgba(1, 0.16));
    core.addColorStop(1, this._rgba(2, 0));
    ctx.fillStyle = core;
    ctx.beginPath();
    ctx.arc(x, y, this.focus.r * (1.6 + this.beatEnergy * 0.5), 0, Math.PI * 2);
    ctx.fill();

    this._drawRipples(ctx, x, y, this.focus.r, maxR);
  }

  /* --- shared bits --- */

  _drawRipples(ctx, x, y, minR, maxR) {
    for (const ripple of this.ripples) {
      const t = 1 - ripple.life;
      const radius = minR + (maxR - minR) * t;
      ctx.strokeStyle = this._rgba(ripple.color, ripple.life * ripple.life * 0.5 * ripple.strength);
      ctx.lineWidth = 1 + ripple.strength * 3 * ripple.life;
      ctx.beginPath();
      ctx.arc(x, y, radius, 0, Math.PI * 2);
      ctx.stroke();
    }
  }

  _drawFloatingMotes(ctx, intensity) {
    const { width, height } = this;
    for (const o of this.orbiters) {
      const level = this.bars[o.band];
      // Deterministic drift from the orbiter's own seed: no per-frame allocation, and the
      // field stays stable across resizes instead of reshuffling.
      const px = ((Math.sin(o.angle * 3.7) * 0.5 + 0.5) * width + this.time * o.speed * 26) % width;
      const py = (((Math.cos(o.angle * 2.3) * 0.5 + 0.5) * height - this.time * o.speed * 18) % height + height) % height;
      const twinkle = 0.5 + 0.5 * Math.sin(this.time * 2.4 + o.twinkle);
      ctx.fillStyle = this._rgba(o.color, (0.08 + level * 0.35) * twinkle * intensity);
      ctx.beginPath();
      ctx.arc(px, py, o.size * (0.5 + level), 0, Math.PI * 2);
      ctx.fill();
    }
  }

  _drawParticles(ctx) {
    for (const p of this.particles) {
      const alpha = clamp01(p.life) * 0.9;
      ctx.fillStyle = this._rgba(p.color, alpha);
      ctx.beginPath();
      ctx.arc(p.x, p.y, p.size * (0.4 + p.life), 0, Math.PI * 2);
      ctx.fill();
    }
  }
}

function roundedRect(ctx, x, y, w, h, r) {
  const radius = Math.max(0, Math.min(r, w / 2, h / 2));
  ctx.beginPath();
  ctx.moveTo(x + radius, y);
  ctx.arcTo(x + w, y, x + w, y + h, radius);
  ctx.arcTo(x + w, y + h, x, y + h, radius);
  ctx.arcTo(x, y + h, x, y, radius);
  ctx.arcTo(x, y, x + w, y, radius);
  ctx.closePath();
}

// Older WebViews silently ignore ctx.filter; assigning and reading it back is the only
// reliable feature test, and bloom is skipped rather than drawn unblurred (which would just
// look like a bright smear).
function supportsFilter(ctx) {
  try {
    ctx.filter = "blur(1px)";
    const ok = ctx.filter === "blur(1px)";
    ctx.filter = "none";
    return ok;
  } catch (err) {
    return false;
  }
}
