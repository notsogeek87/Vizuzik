// Matches AudioCaptureService's BAND_COUNT on the native side (android/.../AudioCaptureService.java)
// so live levels map 1:1 onto bars with no interpolation needed.
const BAR_COUNT = 32;
const PARTICLE_MAX = 60;
const LIVE_LEVELS_TIMEOUT_MS = 500;

function clamp01(value) {
  return value < 0 ? 0 : value > 1 ? 1 : value;
}

/**
 * WMP-style canvas visualizer. When AudioCaptureService is capturing Deezer's real audio
 * output (Android 10+, user-granted), bars follow the actual spectrum via setLevels(). If
 * capture is unavailable or hasn't reported in a while, motion falls back to a simulated
 * pseudo-beat (layered oscillators + a randomly-timed "kick" scheduler) so it never looks frozen.
 */
export class Visualizer {
  constructor(canvas) {
    this.canvas = canvas;
    this.ctx = canvas.getContext("2d");
    this.running = false;
    this.rafId = null;
    this.isPlaying = false;
    this.style = "bars";

    this.hue = 265;
    this.targetHue = 265;
    this.saturation = 70;

    this.bars = new Array(BAR_COUNT).fill(0);
    this.particles = [];
    this.avgLevel = 0;

    this.beatEnergy = 0;
    this.nextBeatAt = 0;
    this.startTime = 0;

    this.liveLevels = null;
    this.lastLiveAt = 0;
    this.prevBass = 0;
    this.liveSilenceSince = null;

    this._onResize = () => this.resize();
    window.addEventListener("resize", this._onResize);
  }

  start() {
    this.resize();
    if (this.running) return;
    this.running = true;
    this.startTime = performance.now();
    this.nextBeatAt = this.startTime + 400;
    this._loop();
  }

  stop() {
    this.running = false;
    if (this.rafId != null) {
      cancelAnimationFrame(this.rafId);
      this.rafId = null;
    }
  }

  setPlaying(isPlaying) {
    this.isPlaying = isPlaying;
  }

  /** Switches the visual style ("bars" or "radial"); same underlying levels drive both. */
  setStyle(style) {
    this.style = style;
  }

  /** Feeds a real-time loudness spectrum (0..1 per band) from AudioCaptureService. */
  setLevels(levels) {
    if (!levels || !levels.length) return;
    this.liveLevels = levels;
    this.lastLiveAt = performance.now();
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
    const hasLiveLevels = this.isPlaying && this.liveLevels && now - this.lastLiveAt < LIVE_LEVELS_TIMEOUT_MS;
    if (!hasLiveLevels) return "simulated";
    if (this.liveSilenceSince != null && now - this.liveSilenceSince > 3000) return "silent";
    return "live";
  }

  resize() {
    const rect = this.canvas.getBoundingClientRect();
    if (rect.width === 0 || rect.height === 0) return;
    const dpr = window.devicePixelRatio || 1;
    this.canvas.width = Math.round(rect.width * dpr);
    this.canvas.height = Math.round(rect.height * dpr);
    this.ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    this.width = rect.width;
    this.height = rect.height;
  }

  /** Derives a base hue/saturation from the average color of the album art. */
  setColorFromImage(dataUri) {
    if (!dataUri) return;
    const img = new Image();
    img.onload = () => {
      const size = 12;
      const sample = document.createElement("canvas");
      sample.width = size;
      sample.height = size;
      const sctx = sample.getContext("2d");
      if (!sctx) return;
      sctx.drawImage(img, 0, 0, size, size);
      let r = 0;
      let g = 0;
      let b = 0;
      let count = 0;
      const { data } = sctx.getImageData(0, 0, size, size);
      for (let i = 0; i < data.length; i += 4) {
        const alpha = data[i + 3];
        if (alpha < 32) continue;
        r += data[i];
        g += data[i + 1];
        b += data[i + 2];
        count++;
      }
      if (count === 0) return;
      r /= count;
      g /= count;
      b /= count;
      const [h, s] = rgbToHsl(r, g, b);
      this.targetHue = h;
      this.saturation = Math.max(45, Math.min(85, s));
    };
    img.src = dataUri;
  }

  _loop() {
    if (!this.running) return;
    this.rafId = requestAnimationFrame(() => this._loop());
    this._update();
    this._draw();
  }

  _update() {
    const now = performance.now();
    const hasLiveLevels = this.isPlaying && this.liveLevels && now - this.lastLiveAt < LIVE_LEVELS_TIMEOUT_MS;

    const speed = this.isPlaying ? 1 : 0.35;
    if (hasLiveLevels) {
      this._updateFromLiveLevels();
    } else {
      this._updateSynthetic(now);
    }

    this.hue += (this.targetHue - this.hue) * 0.02;
    this.hue = (this.hue + speed * 0.08) % 360;

    this._updateParticles();
  }

  /** Drives bars directly from the real spectrum reported by AudioCaptureService. */
  _updateFromLiveLevels() {
    let sum = 0;
    for (let i = 0; i < BAR_COUNT; i++) {
      const target = clamp01(this.liveLevels[i] || 0);
      this.bars[i] += (target - this.bars[i]) * 0.55;
      sum += target;
    }
    this.avgLevel = sum / BAR_COUNT;

    const bass = clamp01(((this.liveLevels[0] || 0) + (this.liveLevels[1] || 0) + (this.liveLevels[2] || 0)) / 3);
    const bassRise = Math.max(0, bass - this.prevBass);
    this.prevBass = bass;
    this.beatEnergy = Math.max(this.beatEnergy * 0.9, clamp01(bassRise * 4));

    if (this.avgLevel < 0.03) {
      if (this.liveSilenceSince == null) this.liveSilenceSince = performance.now();
    } else {
      this.liveSilenceSince = null;
    }
  }

  /** No real audio available (unsupported device, capture denied, or stream stalled): fake it. */
  _updateSynthetic(now) {
    if (!this.isPlaying) {
      // Music is paused/stopped: settle down to rest instead of endlessly wobbling — a few
      // frames of decay rather than a hard cut, so it doesn't visibly snap to flat.
      let sum = 0;
      for (let i = 0; i < BAR_COUNT; i++) {
        this.bars[i] += (0 - this.bars[i]) * 0.15;
        sum += this.bars[i];
      }
      this.avgLevel = sum / BAR_COUNT;
      this.beatEnergy *= 0.85;
      return;
    }

    const t = (now - this.startTime) / 1000;

    if (now >= this.nextBeatAt) {
      this.beatEnergy = 1;
      const bpm = 96 + Math.random() * 48;
      this.nextBeatAt = now + 60000 / bpm;
    }
    this.beatEnergy *= 0.92;

    let sum = 0;
    for (let i = 0; i < BAR_COUNT; i++) {
      const freq = 0.6 + i * 0.05;
      const base = 0.5 + 0.5 * Math.sin(t * freq + i * 0.4);
      const wobble = 0.5 * Math.sin(t * freq * 2.7 + i * 0.9);
      const beatShape = 0.6 + 0.4 * Math.sin(i * 1.7);
      const value = clamp01(base * 0.55 + wobble * 0.25 + this.beatEnergy * beatShape * 0.7);
      this.bars[i] = value;
      sum += value;
    }
    this.avgLevel = sum / BAR_COUNT;
  }

  _updateParticles() {
    const spawnChance = this.isPlaying ? 0.12 + this.avgLevel * 0.5 + this.beatEnergy * 0.3 : 0;
    if (Math.random() < spawnChance && this.particles.length < PARTICLE_MAX) {
      this.particles.push({
        x: Math.random() * this.width,
        y: this.height,
        vy: 20 + Math.random() * 40,
        r: 1.5 + Math.random() * 2.5,
        life: 1,
        hue: this.hue + (Math.random() * 60 - 30),
      });
    }
    const dt = 1 / 60;
    this.particles = this.particles.filter((p) => p.life > 0);
    for (const p of this.particles) {
      p.y -= p.vy * dt;
      p.life -= dt * 0.4;
    }
  }

  _draw() {
    const { ctx, width, height } = this;
    if (!width || !height) return;
    ctx.clearRect(0, 0, width, height);
    this._drawBackground();

    if (this.style === "radial") {
      this._drawRadial();
    } else {
      this._drawParticles();
      this._drawBars();
    }
  }

  _drawBackground() {
    const { ctx, width, height } = this;
    const bg = ctx.createRadialGradient(
      width / 2,
      height * 0.65,
      0,
      width / 2,
      height * 0.65,
      Math.max(width, height) * 0.7
    );
    bg.addColorStop(0, `hsla(${this.hue}, ${this.saturation}%, 22%, 1)`);
    bg.addColorStop(1, `hsla(${(this.hue + 40) % 360}, ${this.saturation}%, 6%, 1)`);
    ctx.fillStyle = bg;
    ctx.fillRect(0, 0, width, height);
  }

  _drawParticles() {
    const { ctx } = this;
    for (const p of this.particles) {
      ctx.globalAlpha = clamp01(p.life);
      ctx.fillStyle = `hsl(${p.hue}, 90%, 70%)`;
      ctx.beginPath();
      ctx.arc(p.x, p.y, p.r, 0, Math.PI * 2);
      ctx.fill();
    }
    ctx.globalAlpha = 1;
  }

  _drawBars() {
    const { ctx, width, height } = this;
    const gap = 3;
    const barWidth = (width - gap * (BAR_COUNT - 1)) / BAR_COUNT;
    const maxBarHeight = height * 0.7;
    const baseline = height * 0.82;

    for (let i = 0; i < BAR_COUNT; i++) {
      const h = Math.max(3, this.bars[i] * maxBarHeight);
      const x = i * (barWidth + gap);
      const barHue = (this.hue + (i / BAR_COUNT) * 70) % 360;
      const grad = ctx.createLinearGradient(0, baseline - h, 0, baseline);
      grad.addColorStop(0, `hsl(${barHue}, 95%, 70%)`);
      grad.addColorStop(1, `hsl(${barHue}, 90%, 45%)`);
      ctx.fillStyle = grad;
      roundedRect(ctx, x, baseline - h, barWidth, h, barWidth / 2);
      ctx.fill();

      ctx.globalAlpha = 0.22;
      ctx.fillStyle = `hsl(${barHue}, 90%, 55%)`;
      const reflectH = h * 0.35;
      roundedRect(ctx, x, baseline + 4, barWidth, reflectH, barWidth / 2);
      ctx.fill();
      ctx.globalAlpha = 1;
    }
  }

  /** Kaleidoscope-ish mirrored-petal flower, reactive to the same bars/beat data as the bars style. */
  _drawRadial() {
    const { ctx, width, height } = this;
    const cx = width / 2;
    const cy = height / 2;
    const maxRadius = Math.min(width, height) * 0.46;
    const petals = 8;
    const angleStep = (Math.PI * 2) / petals;
    const samplesPerPetal = Math.max(2, Math.round(BAR_COUNT / petals));

    ctx.save();
    ctx.translate(cx, cy);
    ctx.globalCompositeOperation = "lighter";
    for (let p = 0; p < petals; p++) {
      ctx.save();
      ctx.rotate(p * angleStep + this.hue * 0.002);
      this._drawPetal(maxRadius, samplesPerPetal);
      ctx.restore();
    }
    ctx.globalCompositeOperation = "source-over";
    ctx.restore();

    const glowRadius = maxRadius * (0.08 + this.beatEnergy * 0.06);
    const glow = ctx.createRadialGradient(cx, cy, 0, cx, cy, glowRadius);
    glow.addColorStop(0, `hsla(${this.hue}, 95%, 85%, 0.9)`);
    glow.addColorStop(1, `hsla(${this.hue}, 95%, 60%, 0)`);
    ctx.fillStyle = glow;
    ctx.beginPath();
    ctx.arc(cx, cy, glowRadius, 0, Math.PI * 2);
    ctx.fill();
  }

  _drawPetal(maxRadius, sampleCount) {
    const { ctx } = this;
    const halfAngle = ((Math.PI * 2) / 8 / 2) * 0.85;

    ctx.beginPath();
    ctx.moveTo(0, 0);
    for (let i = 0; i <= sampleCount; i++) {
      const tt = i / sampleCount;
      const angle = -halfAngle + tt * halfAngle * 2;
      const level = this.bars[i % BAR_COUNT];
      const r = maxRadius * (0.12 + level * 0.88);
      const x = Math.sin(angle) * r;
      const y = -Math.cos(angle) * r;
      ctx.lineTo(x, y);
    }
    ctx.closePath();

    const grad = ctx.createRadialGradient(0, 0, 0, 0, 0, maxRadius);
    grad.addColorStop(0, `hsla(${this.hue}, 95%, 70%, 0.85)`);
    grad.addColorStop(0.6, `hsla(${(this.hue + 40) % 360}, 90%, 55%, 0.5)`);
    grad.addColorStop(1, `hsla(${(this.hue + 80) % 360}, 90%, 45%, 0)`);
    ctx.fillStyle = grad;
    ctx.fill();
  }
}

function roundedRect(ctx, x, y, w, h, r) {
  const radius = Math.min(r, w / 2, h / 2);
  ctx.beginPath();
  ctx.moveTo(x + radius, y);
  ctx.arcTo(x + w, y, x + w, y + h, radius);
  ctx.arcTo(x + w, y + h, x, y + h, radius);
  ctx.arcTo(x, y + h, x, y, radius);
  ctx.arcTo(x, y, x + w, y, radius);
  ctx.closePath();
}

function rgbToHsl(r, g, b) {
  r /= 255;
  g /= 255;
  b /= 255;
  const max = Math.max(r, g, b);
  const min = Math.min(r, g, b);
  let h = 0;
  const l = (max + min) / 2;
  const d = max - min;
  const s = d === 0 ? 0 : d / (1 - Math.abs(2 * l - 1));
  if (d !== 0) {
    switch (max) {
      case r:
        h = ((g - b) / d) % 6;
        break;
      case g:
        h = (b - r) / d + 2;
        break;
      default:
        h = (r - g) / d + 4;
    }
    h *= 60;
    if (h < 0) h += 360;
  }
  return [h, s * 100];
}
