// Pulls a small, vivid palette out of the album art so every glow, gradient and particle in
// the app is tinted by the record actually playing. Runs on a 32x32 downscale — cheap enough
// to redo on every track change, precise enough to find the artwork's real accent colors.

const SAMPLE_SIZE = 32;

// Neutral fallback (indigo / violet / cyan) for artwork that is greyscale, missing, or too
// washed out to yield a confident accent — better a deliberate palette than a muddy brown.
const FALLBACK = {
  colors: [
    [124, 92, 255],
    [236, 72, 153],
    [56, 189, 248],
  ],
  hue: 258,
  saturation: 72,
};

export function extractPalette(source) {
  return new Promise((resolve) => {
    if (!source) {
      resolve(FALLBACK);
      return;
    }
    const img = new Image();
    img.crossOrigin = "anonymous";
    img.onload = () => {
      try {
        resolve(analyse(img));
      } catch (err) {
        resolve(FALLBACK);
      }
    };
    img.onerror = () => resolve(FALLBACK);
    img.src = source;
  });
}

function analyse(img) {
  const canvas = document.createElement("canvas");
  canvas.width = SAMPLE_SIZE;
  canvas.height = SAMPLE_SIZE;
  const ctx = canvas.getContext("2d", { willReadFrequently: true });
  if (!ctx) return FALLBACK;
  ctx.drawImage(img, 0, 0, SAMPLE_SIZE, SAMPLE_SIZE);
  const { data } = ctx.getImageData(0, 0, SAMPLE_SIZE, SAMPLE_SIZE);

  // Bucket by hue (20 degree slices) crossed with a coarse lightness band, so a bright red and
  // a dark maroon don't collapse into one average that matches neither.
  const buckets = new Map();
  for (let i = 0; i < data.length; i += 4) {
    if (data[i + 3] < 32) continue;
    const r = data[i];
    const g = data[i + 1];
    const b = data[i + 2];
    const [h, s, l] = rgbToHsl(r, g, b);
    // Score favours saturated mid-tones: those are what read as "the color of this cover",
    // while near-black and near-white pixels are usually just background.
    const score = (0.15 + s / 100) * Math.max(0.05, 1 - Math.abs(l / 100 - 0.55) * 1.6);
    const key = `${Math.floor(h / 20)}:${Math.floor(l / 25)}`;
    let bucket = buckets.get(key);
    if (!bucket) {
      bucket = { r: 0, g: 0, b: 0, score: 0, count: 0 };
      buckets.set(key, bucket);
    }
    bucket.r += r * score;
    bucket.g += g * score;
    bucket.b += b * score;
    bucket.score += score;
    bucket.count++;
  }

  const ranked = [...buckets.values()]
    .filter((bucket) => bucket.score > 0)
    .map((bucket) => {
      const r = bucket.r / bucket.score;
      const g = bucket.g / bucket.score;
      const b = bucket.b / bucket.score;
      const [h, s, l] = rgbToHsl(r, g, b);
      return { h, s, l, score: bucket.score };
    })
    .sort((a, b) => b.score - a.score);

  if (!ranked.length || ranked[0].s < 8) return FALLBACK;

  // Greedy pick of hues at least 32 degrees apart: three near-identical blues would make the
  // whole UI look flat, so spread the accents out even if that means a lower-scoring bucket.
  const picked = [];
  for (const candidate of ranked) {
    if (picked.every((chosen) => hueDistance(chosen.h, candidate.h) > 32)) picked.push(candidate);
    if (picked.length === 3) break;
  }
  // Not enough distinct hues on the cover (mono-color artwork): fan out from the dominant one
  // rather than repeating it three times.
  while (picked.length < 3) {
    const base = picked[0] || ranked[0];
    picked.push({ h: (base.h + 40 * picked.length) % 360, s: base.s, l: base.l, score: 0 });
  }

  const dominant = picked[0];
  return {
    colors: picked.map((c) => hslToRgb(c.h, clamp(c.s * 1.25, 45, 92), clamp(c.l * 1.05, 48, 68))),
    hue: dominant.h,
    saturation: clamp(dominant.s * 1.15, 45, 88),
  };
}

function hueDistance(a, b) {
  const d = Math.abs(a - b) % 360;
  return d > 180 ? 360 - d : d;
}

function clamp(value, min, max) {
  return value < min ? min : value > max ? max : value;
}

export function rgbToHsl(r, g, b) {
  r /= 255;
  g /= 255;
  b /= 255;
  const max = Math.max(r, g, b);
  const min = Math.min(r, g, b);
  const l = (max + min) / 2;
  const d = max - min;
  let h = 0;
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
  return [h, s * 100, l * 100];
}

export function hslToRgb(h, s, l) {
  const sat = s / 100;
  const lum = l / 100;
  const c = (1 - Math.abs(2 * lum - 1)) * sat;
  const x = c * (1 - Math.abs(((h / 60) % 2) - 1));
  const m = lum - c / 2;
  let rgb;
  if (h < 60) rgb = [c, x, 0];
  else if (h < 120) rgb = [x, c, 0];
  else if (h < 180) rgb = [0, c, x];
  else if (h < 240) rgb = [0, x, c];
  else if (h < 300) rgb = [x, 0, c];
  else rgb = [c, 0, x];
  return rgb.map((v) => Math.round((v + m) * 255));
}
