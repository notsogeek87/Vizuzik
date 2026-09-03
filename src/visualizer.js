const BAR_COUNT = 40;
const PARTICLE_MAX = 60;

function clamp01(value) {
  return value < 0 ? 0 : value > 1 ? 1 : value;
}

/**
 * Ambient, WMP-style canvas visualizer. There is no access to the actual audio
 * stream (Vizuzik only reads Deezer's MediaSession metadata), so the motion is a
 * simulated pseudo-beat: layered oscillators give an organic base motion, and a
 * randomly-timed "kick" scheduler injects punchy pulses so bars and particles feel
 * like they're following a rhythm rather than just drifting.
 */
export class Visualizer {
  constructor(canvas) {
    this.canvas = canvas;
    this.ctx = canvas.getContext("2d");
    this.running = false;
    this.rafId = null;
    this.isPlaying = false;

    this.hue = 265;
    this.targetHue = 265;
    this.saturation = 70;

    this.bars = new Array(BAR_COUNT).fill(0);
    this.particles = [];

    this.beatEnergy = 0;
    this.nextBeatAt = 0;
    this.startTime = 0;

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
    const t = (now - this.startTime) / 1000;

    if (now >= this.nextBeatAt) {
      this.beatEnergy = 1;
      const bpm = 96 + Math.random() * 48;
      this.nextBeatAt = now + 60000 / bpm;
    }
    this.beatEnergy *= 0.92;

    const intensity = this.isPlaying ? 1 : 0.25;
    const speed = this.isPlaying ? 1 : 0.35;

    for (let i = 0; i < BAR_COUNT; i++) {
      const freq = 0.6 + i * 0.05;
      const base = 0.5 + 0.5 * Math.sin(t * speed * freq + i * 0.4);
      const wobble = 0.5 * Math.sin(t * speed * freq * 2.7 + i * 0.9);
      const beatShape = 0.6 + 0.4 * Math.sin(i * 1.7);
      let value = base * 0.55 + wobble * 0.25 + this.beatEnergy * beatShape * 0.7;
      this.bars[i] = clamp01(value * intensity);
    }

    this.hue += (this.targetHue - this.hue) * 0.02;
    this.hue = (this.hue + speed * 0.08) % 360;

    if (this.isPlaying && Math.random() < 0.15 + this.beatEnergy * 0.4 && this.particles.length < PARTICLE_MAX) {
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

    for (const p of this.particles) {
      ctx.globalAlpha = clamp01(p.life);
      ctx.fillStyle = `hsl(${p.hue}, 90%, 70%)`;
      ctx.beginPath();
      ctx.arc(p.x, p.y, p.r, 0, Math.PI * 2);
      ctx.fill();
    }
    ctx.globalAlpha = 1;

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
