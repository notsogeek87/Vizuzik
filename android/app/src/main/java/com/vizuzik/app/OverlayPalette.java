package com.vizuzik.app;

import android.graphics.Bitmap;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Hand-ported equivalent of src/palette.js's extractPalette()/analyse(): buckets the artwork's
 * pixels by hue and lightness, scores each bucket toward saturated mid-tones, and greedily picks
 * up to three accents at least 32° of hue apart. OverlayEdgeGlowService needs this because it
 * runs as a plain Service, entirely outside the webview — there is no DOM canvas to reuse here,
 * so the two implementations are kept in step by hand rather than bridged. Same constants as the
 * JS version throughout, so a track's overlay glow and its full-screen Vizuzik palette agree.
 */
final class OverlayPalette {

    private static final int SAMPLE_SIZE = 32;
    private static final int[][] FALLBACK = {
        { 124, 92, 255 },
        { 236, 72, 153 },
        { 56, 189, 248 },
    };

    private OverlayPalette() {}

    static int[][] extract(Bitmap source) {
        if (source == null) {
            return FALLBACK;
        }

        Bitmap scaled;
        try {
            scaled = Bitmap.createScaledBitmap(source, SAMPLE_SIZE, SAMPLE_SIZE, true);
        } catch (Exception e) {
            return FALLBACK;
        }

        int[] pixels = new int[SAMPLE_SIZE * SAMPLE_SIZE];
        scaled.getPixels(pixels, 0, SAMPLE_SIZE, 0, 0, SAMPLE_SIZE, SAMPLE_SIZE);
        if (scaled != source) {
            scaled.recycle();
        }

        Map<String, Bucket> buckets = new HashMap<>();
        for (int pixel : pixels) {
            int alpha = (pixel >>> 24) & 0xFF;
            if (alpha < 32) continue;
            int r = (pixel >>> 16) & 0xFF;
            int g = (pixel >>> 8) & 0xFF;
            int b = pixel & 0xFF;
            float[] hsl = rgbToHsl(r, g, b);
            float h = hsl[0];
            float s = hsl[1];
            float l = hsl[2];
            // Score favours saturated mid-tones: those read as "the color of this cover", while
            // near-black and near-white pixels are usually just background.
            double score = (0.15 + s / 100.0) * Math.max(0.05, 1 - Math.abs(l / 100.0 - 0.55) * 1.6);
            String key = ((int) (h / 20)) + ":" + ((int) (l / 25));
            Bucket bucket = buckets.get(key);
            if (bucket == null) {
                bucket = new Bucket();
                buckets.put(key, bucket);
            }
            bucket.r += r * score;
            bucket.g += g * score;
            bucket.b += b * score;
            bucket.score += score;
        }

        List<Ranked> ranked = new ArrayList<>();
        for (Bucket bucket : buckets.values()) {
            if (bucket.score <= 0) continue;
            float r = (float) (bucket.r / bucket.score);
            float g = (float) (bucket.g / bucket.score);
            float b = (float) (bucket.b / bucket.score);
            float[] hsl = rgbToHsl(r, g, b);
            ranked.add(new Ranked(hsl[0], hsl[1], hsl[2], bucket.score));
        }
        ranked.sort((a, b) -> Double.compare(b.score, a.score));

        if (ranked.isEmpty() || ranked.get(0).s < 8) {
            return FALLBACK;
        }

        // Greedy pick of hues at least 32 degrees apart: three near-identical blues would make
        // the glow look flat, so spread the accents out even if that means a lower-scoring bucket.
        List<Ranked> picked = new ArrayList<>();
        for (Ranked candidate : ranked) {
            boolean farEnough = true;
            for (Ranked chosen : picked) {
                if (hueDistance(chosen.h, candidate.h) <= 32) {
                    farEnough = false;
                    break;
                }
            }
            if (farEnough) picked.add(candidate);
            if (picked.size() == 3) break;
        }
        // Not enough distinct hues on the cover (mono-color artwork): fan out from the dominant
        // one rather than repeating it three times.
        Ranked base = picked.isEmpty() ? ranked.get(0) : picked.get(0);
        while (picked.size() < 3) {
            float h = (base.h + 40 * picked.size()) % 360;
            picked.add(new Ranked(h, base.s, base.l, 0));
        }

        int[][] colors = new int[3][];
        for (int i = 0; i < 3; i++) {
            Ranked c = picked.get(i);
            colors[i] = hslToRgb(c.h, clamp(c.s * 1.25f, 45, 92), clamp(c.l * 1.05f, 48, 68));
        }
        return colors;
    }

    private static float hueDistance(float a, float b) {
        float d = Math.abs(a - b) % 360;
        return d > 180 ? 360 - d : d;
    }

    private static float clamp(float value, float min, float max) {
        return value < min ? min : Math.min(value, max);
    }

    private static float[] rgbToHsl(float r, float g, float b) {
        r /= 255f;
        g /= 255f;
        b /= 255f;
        float max = Math.max(r, Math.max(g, b));
        float min = Math.min(r, Math.min(g, b));
        float l = (max + min) / 2f;
        float d = max - min;
        float h = 0f;
        float s = d == 0 ? 0 : d / (1 - Math.abs(2 * l - 1));
        if (d != 0) {
            if (max == r) h = ((g - b) / d) % 6;
            else if (max == g) h = (b - r) / d + 2;
            else h = (r - g) / d + 4;
            h *= 60;
            if (h < 0) h += 360;
        }
        return new float[] { h, s * 100, l * 100 };
    }

    private static int[] hslToRgb(float h, float s, float l) {
        float sat = s / 100f;
        float lum = l / 100f;
        float c = (1 - Math.abs(2 * lum - 1)) * sat;
        float x = c * (1 - Math.abs((h / 60f) % 2 - 1));
        float m = lum - c / 2f;
        float rr;
        float gg;
        float bb;
        if (h < 60) { rr = c; gg = x; bb = 0; }
        else if (h < 120) { rr = x; gg = c; bb = 0; }
        else if (h < 180) { rr = 0; gg = c; bb = x; }
        else if (h < 240) { rr = 0; gg = x; bb = c; }
        else if (h < 300) { rr = x; gg = 0; bb = c; }
        else { rr = c; gg = 0; bb = x; }
        return new int[] {
            Math.round((rr + m) * 255),
            Math.round((gg + m) * 255),
            Math.round((bb + m) * 255),
        };
    }

    private static final class Bucket {
        double r;
        double g;
        double b;
        double score;
    }

    private static final class Ranked {
        final float h;
        final float s;
        final float l;
        final double score;

        Ranked(float h, float s, float l, double score) {
            this.h = h;
            this.s = s;
            this.l = l;
            this.score = score;
        }
    }
}
