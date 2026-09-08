package com.vizuzik.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Rect;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;

/**
 * Draws a thin, colored glow along the screen edges, breathing with the music underneath — the
 * one visual this view is allowed to show, since OverlayEdgeGlowService adds it as a
 * touch-transparent window on top of whatever app is in front (Deezer/Spotify/a local player,
 * typically). A full-screen overlay was deliberately rejected (it would make the app underneath
 * illegible); an edge-only glow, MuViz Edge-style, keeps it completely usable.
 *
 * Two regimes, same rule as src/visualizer.js's ambient/live split: with real audio levels
 * flowing in from AudioLevelsBridge, the glow's thickness and a beat pulse follow the actual
 * bass; without it (capture not granted, or momentarily stalled), the glow breathes on its own —
 * quickly and visibly enough to read as alive on a glance — but never claims to follow a beat it
 * never captured. In both regimes the color drifts across the current track's three accent
 * colors (or a fixed custom palette, see EdgeConfig), and a real event (track change, play/pause)
 * is still allowed an honest pulse.
 *
 * Four rendering styles, picked in the settings panel (EdgeConfig): "bars", the default, drawing
 * each of the 32 bands on its own; "glow", the border that averages them into one scalar;
 * "cocoon", a ribbon woven around Deezer's own album art; and "vinyl", that same artwork redrawn
 * as a spinning record in the same spot — see drawCocoon() and drawVinyl() below for why and how
 * those two aren't edge-only like the first two.
 */
final class EdgeGlowView extends View {

    private static final String TAG = "EdgeGlowView";
    // A plain Handler loop rather than Choreographer.postFrameCallback(): this view belongs to a
    // Service's overlay window, not an Activity, and Choreographer's vsync-driven callback can
    // simply never fire a second time for such a window on some devices/Android builds — leaving
    // the very first frame on screen forever. A Handler tied to the main Looper's own message
    // queue has no such dependency on the window being considered for vsync by the system; a
    // plain border glow doesn't need frame-perfect vsync timing anyway.
    // ~30fps. Was 24, which is enough for a border glow but reads as choppy on the cocoon's
    // travelling weave; affordable now that a frame no longer recomputes the ribbon's geometry
    // from scratch.
    private static final long FRAME_INTERVAL_MS = 33;
    private static final int[][] FALLBACK_PALETTE = {
        { 124, 92, 255 },
        { 236, 72, 153 },
        { 56, 189, 248 },
    };
    private static final long PALETTE_BLEND_MS = 700;
    private static final long COLOR_TRAVEL_MS = 9_000;
    private static final int BEAT_HISTORY = 48;
    // How long a pulse (a real beat, or pulse() from a track change / play-pause) stays visible.
    // This view's border is the only thing carrying that impulse, so it needs to linger for
    // roughly a second and a half to actually register on a glance instead of flashing briefly.
    private static final float PULSE_DECAY_MS = 55f;
    /** Past this long without a single level, the capture counts as gone and ambient takes over. */
    private static final long LIVE_LEVELS_TIMEOUT_MS = 1_200;

    // 32 bands, 55 Hz-7000 Hz logarithmic — the layout TrackedSessionAudioSource produces.
    // Rough index ranges for EdgeConfig's frequency choice: bass ~55-190 Hz (0-7),
    // mid ~190-1700 Hz (8-21), treble ~1700-7000 Hz (22-31).
    private static final int BASS_END = 8;
    private static final int MID_END = 22;
    // How far a bar may reach inward, as a fraction of the screen's *smaller* dimension —
    // deliberately not of the one it grows along. On a wide screen (a phone unfolded, a tablet)
    // that would let the left and right rows each run almost a third of the way across, and the
    // overlay is meant to frame the tracked app, not bury it. Well under half for the same
    // reason: opposite edges are both on by default and must never meet in the middle.
    private static final float BAR_MAX_FRACTION = 0.2f;

    // Where the music app's own now-playing album art sits. This view has no way to read another
    // app's actual view bounds — there is no accessibility hook wired up for that, and screen
    // capture was deliberately dropped from this app (see TrackedAudioCapture: MediaProjection
    // made Android show its "start recording your screen" dialog every time) — so the two styles
    // anchored to the cover work from a *model* of how that screen is laid out.
    //
    // A model, not one phone's measurements: a player of this shape sizes its cover as a square
    // that is capped twice over — by the screen's width, and by the room left between the header
    // and the stack of controls underneath. Whichever cap is smaller wins, which is exactly what
    // lets one set of numbers survive a screen they were never measured on: on a tall phone the
    // width cap binds and the cover comes out nearly full width, on a short or unfolded one the
    // height cap binds and it shrinks instead of running off the screen.
    //
    // Calibrated against a Z Fold, both layouts:
    //   folded   1248x1823 — one column: cover side 732 px (the height cap: 0.4015 x 1823), top
    //                        edge 188 px down (0.103 x 1823), centred horizontally
    //   unfolded 2448x1575 — two panes, the cover in the left one: side 975 px (the height cap
    //                        again, 0.619 x 1575), centred on the first quarter of the width
    //
    // Anything the model still gets wrong on a given phone is what the calibration handle is for
    // — see setArtCalibrationFromScreenCentre() and ArtCalibrationPuck.
    private static final float ART_TALL_CENTER_X_FRACTION = 0.5f;
    private static final float ART_TALL_TOP_FRACTION = 0.103f;
    private static final float ART_TALL_MAX_WIDTH_FRACTION = 0.88f;
    private static final float ART_TALL_MAX_HEIGHT_FRACTION = 0.4015f;
    private static final float ART_WIDE_CENTER_X_FRACTION = 0.25f;
    private static final float ART_WIDE_CENTER_Y_FRACTION = 0.5f;
    private static final float ART_WIDE_MAX_HEIGHT_FRACTION = 0.619f;
    private static final float ART_WIDE_MAX_PANE_FRACTION = 0.80f;
    /** How far the calibration handle may push the anchor, as a fraction of the screen — enough
     *  to reach any layout, bounded so a stray drag can never park it off-screen for good. */
    private static final float ART_OFFSET_LIMIT = 0.45f;
    private static final float ART_SCALE_MIN = 0.4f;
    private static final float ART_SCALE_MAX = 2.2f;

    // The cocoon bundle, in multiples of the artwork's half-size. What makes it read as a ribbon
    // of light rather than a few loops is the density: two dozen hairlines packed into a narrow
    // band. Three thick translucent lines — the first attempt — just looked like three lines.
    //
    // The path is a superellipse, not a circle: the thing it frames is a square cover, and a
    // circle around a square leaves gaps at the edge midpoints and crowds the corners.
    private static final int COCOON_STRANDS = 12;
    private static final int COCOON_SPOKES = 96;
    // Strands are stroked in three brightness tiers rather than one draw each, so a frame issues
    // six stroked paths instead of sixty-six. A Path holds as many subpaths as it likes.
    private static final int COCOON_TIERS = 3;
    private static final float COCOON_INNER = 1.045f;
    private static final float COCOON_BAND = 0.20f;
    private static final float COCOON_SWING = 0.085f;
    private static final float COCOON_SHEAR = 0.45f;
    // What it frames is a square cover, so the ribbon has to read as a square with rounded
    // corners. The exponent is how far round that shape sits between a circle and a true square:
    // at the corner, 3.4 reached only 37% of the way there and looked like a blob, 8 reaches 72%
    // and matches the artwork's own corner rounding.
    private static final float COCOON_SQUIRCLE = 8f;
    private static final float COCOON_LINE = 0.62f;
    private static final int COCOON_SHADOW_ALPHA = 33;
    /** How far the ribbon's colours are pushed away from grey — see buildCocoonSweep(). */
    private static final float COCOON_SATURATION = 2.3f;
    // How far the ribbon's hue is turned away from the album's own. Deezer tints its now-playing
    // page from the very artwork the palette is extracted from, so an unturned ribbon lands on
    // roughly the colour of the page behind it and disappears into it — on a green cover it was
    // green on green, and no amount of saturation rescues that. Near-complementary: still the
    // track's own colour, answered rather than repeated, and it changes with every track. The
    // "Couleurs / Personnalisées" setting bypasses this entirely.
    private static final float COCOON_HUE_TURN = 150f;
    // Sum of the three lobe amplitudes below, used to bias the wave into 0..1 so it can only
    // ever push a strand outward: this view draws on top of the music app, so anything that
    // dipped inward would crawl across the album art it is supposed to be framing.
    private static final float COCOON_WAVE_MAX = 0.085f + 0.042f + 0.055f;

    // "Vinyl": the same 16s-per-turn rate the web player's own .disc__spin uses, so the overlay
    // reads as the same object rather than a different speed invented for the native side. A
    // physical turntable's platter doesn't speed up with the music, so unlike the cocoon's
    // phases this never reacts to loudness — only to whether the track is actually playing.
    private static final float VINYL_DEG_PER_SEC = 360f / 16f;
    private static final int VINYL_GROOVES = 9;
    // Where the grooves start and how far they reach, as a fraction of the disc's own radius —
    // clear of the label in the middle and short of the rim, the same band a pressed record's
    // grooves actually occupy.
    private static final float VINYL_GROOVE_START = 0.34f;
    private static final float VINYL_GROOVE_SPAN = 0.60f;
    // A little wider than the web player's own .disc__label, which is sized for a screen where
    // nothing sits behind it: over the music app's still cover, the label is what says "record"
    // at a glance, and at 13% of the radius it was too small to say it.
    private static final float VINYL_LABEL_FRACTION = 0.17f;
    // Same --void CSS variable the web player's own .cover background sits on (#14141f).
    private static final int VINYL_VOID_COLOR = 0xFF14141F;
    /** How far past the record's own edge its shadow reaches, as a multiple of the radius. */
    private static final float VINYL_SHADOW_REACH = 1.09f;

    // Everything about the ribbon that depends only on where you are around it, computed once at
    // class load: the superellipse radius (three Math.pow calls each), the unit vector, and the
    // sines and cosines of the three lobe frequencies. All of it used to be recomputed for every
    // strand of every frame even though it is identical across strands and never changes —
    // roughly 226,000 Math.pow and 377,000 trig calls a second on the main thread, which is what
    // made the ribbon stutter. What is left per strand is six trig calls for its own phase
    // offsets, and the angle-sum identity turns the rest into multiply-adds.
    private static final float[] COCOON_COS = new float[COCOON_SPOKES + 1];
    private static final float[] COCOON_SIN = new float[COCOON_SPOKES + 1];
    private static final float[] COCOON_SHAPE = new float[COCOON_SPOKES + 1];
    private static final float[] COCOON_SIN3 = new float[COCOON_SPOKES + 1];
    private static final float[] COCOON_COS3 = new float[COCOON_SPOKES + 1];
    private static final float[] COCOON_SIN5 = new float[COCOON_SPOKES + 1];
    private static final float[] COCOON_COS5 = new float[COCOON_SPOKES + 1];
    private static final float[] COCOON_SIN2 = new float[COCOON_SPOKES + 1];
    private static final float[] COCOON_COS2 = new float[COCOON_SPOKES + 1];

    static {
        for (int i = 0; i <= COCOON_SPOKES; i++) {
            double a = i / (double) COCOON_SPOKES * Math.PI * 2;
            COCOON_COS[i] = (float) Math.cos(a);
            COCOON_SIN[i] = (float) Math.sin(a);
            COCOON_SHAPE[i] = squircle((float) a);
            COCOON_SIN3[i] = (float) Math.sin(a * 3);
            COCOON_COS3[i] = (float) Math.cos(a * 3);
            COCOON_SIN5[i] = (float) Math.sin(a * 5);
            COCOON_COS5[i] = (float) Math.cos(a * 5);
            COCOON_SIN2[i] = (float) Math.sin(a * 2);
            COCOON_COS2[i] = (float) Math.cos(a * 2);
        }
    }

    private final Paint paint = new Paint();
    // One Path per brightness tier, each holding several strands as subpaths. Allocated once and
    // rebuilt in place: allocating Paths per frame would be pure waste.
    private final Path[] cocoonTiers = new Path[COCOON_TIERS];
    // The spectrum sampled once per frame around the ribbon: it depends on the angle, not on the
    // strand, so sampling it inside the strand loop did the same work 22 times over.
    private final float[] cocoonLevels = new float[COCOON_SPOKES + 1];
    private final float density;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = this::onTick;

    private int[][] fromPalette = FALLBACK_PALETTE;
    private int[][] toPalette = FALLBACK_PALETTE;
    private long paletteBlendStartMs;

    private float colorShift;
    private long lastFrameMs;

    // Written from the audio-capture thread, read from the UI thread on every frame: plain
    // volatile fields rather than a lock: a decorative glow can tolerate a one-frame-old value,
    // but must never block the capture loop.
    //
    // "Live" is a timestamp rather than a boolean on purpose: a boolean set on the first level
    // and never cleared is exactly how the glow used to freeze — one silent death of the capture
    // and the last received level stayed on screen forever. Levels going stale now falls back to
    // the ambient regime on its own.
    private volatile long lastLevelsAtMs;
    private volatile float level;
    private volatile boolean pendingBeat;

    private final float[] bassHistory = new float[BEAT_HISTORY];
    private int bassCursor;
    private long lastBeatAtMs;
    private float beatEnergy;

    // The cocoon's animation phases. Accumulated per frame rather than derived from the clock:
    // their speeds rise with the music, and phase = elapsedTime * speed would jump violently the
    // moment a speed changed — elapsedRealtime() is already in the tens of thousands of seconds
    // by the time anyone opens the app, so even a small change in speed moves it hugely. Adding
    // speed * dt each frame keeps the motion continuous however the speed moves, and keeps the
    // values small enough for a float to still resolve them.
    private float cocoonWaveA;
    private float cocoonWaveB;
    private float cocoonSweep;
    private float cocoonShear;
    private float cocoonOrbit;
    private float cocoonTwinkle;

    // The vinyl's own rotation, accumulated the same way as the cocoon's phases rather than
    // derived from the clock — see the field comments above for why. Frozen exactly where it
    // is, with no ease-out, the instant playback pauses: same as the web player's own
    // animation-play-state toggle, and how a real deck's platter actually stops.
    private float vinylAngleDeg;
    private volatile boolean vinylPlaying = true;
    // The last artwork handed over for "vinyl" — see setAlbumArt(). Read from the main thread
    // only (set from DeezerMediaBridge's callback, which also runs on the main thread), so a
    // plain reference is enough; the shader is rebuilt once per track rather than per frame.
    private Bitmap vinylBitmap;
    private BitmapShader vinylShader;
    private final Matrix vinylMatrix = new Matrix();
    // Its own Paint rather than the shared one every other style passes around: the record is
    // built from a dozen draws whose alphas differ wildly, and one of them inheriting another's
    // is exactly how the label ended up drawn at 10% opacity, making the whole disc look
    // see-through. Nothing outside drawVinyl() ever touches this.
    private final Paint vinylPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Shader vinylVignette;
    private Shader vinylShadow;
    private float vinylShadersForHalf;

    // The bars style used to draw whatever the capture last handed over, raw, which flickers:
    // consecutive frames of a real spectrum jump around a lot. These follow it with an
    // asymmetric ease — snap up on a transient, glide back down — the same shape the full-screen
    // player uses, and the reason a spectrum feels like it is dancing rather than twitching.
    // The peaks are the classic floating caps: they hold the value each band just reached and
    // fall under gravity, which is what shows how hard a hit was after the bar itself has gone.
    private float[] barLevels;
    private float[] barPeaks;
    private float[] barPeakFall;

    // Ambient-only breathing: three periods with no common multiple, so the glow never seems to
    // loop — same idea as _updateAmbient() in visualizer.js, just three oscillators instead of
    // per-band ones since this view has no spectrum to speak of, only a border.
    private float ambientPhase;

    // Last full 32-band array received (whichever source fed it) — kept separately from the
    // single-scalar `level` above so the "bars" diagnostic style can show each band's own value
    // instead of the one number the border glow reduces the whole spectrum to. A plain volatile
    // reference swap, not a copy: TrackedSessionAudioSource/AudioLevelsBridge already hand over a
    // freshly cloned array on every call that's never mutated again afterward, so publishing the
    // reference itself is safe without an extra copy here.
    private volatile float[] lastBands;

    // EdgeConfig-driven knobs, applied by OverlayEdgeGlowService — see applyConfig().
    private String style = EdgeConfig.STYLE_GLOW;
    private String band = EdgeConfig.BAND_FULL;
    private float intensity = 1f;
    private float thicknessMul = 1f;
    private float brightnessMul = 1f;
    private float sensitivity = 1f;
    private float barSize = 1f;
    private int[][] customPalette; // non-null only in "custom" color mode
    private boolean edgeTop = true;
    private boolean edgeBottom = true;
    private boolean edgeLeft = true;
    private boolean edgeRight = true;
    private boolean onlyOverMusicApp = true;
    private String cocoonFallback = EdgeConfig.STYLE_BARS;
    // This phone's own correction to the modelled album-art anchor — see artRect() and
    // EdgeConfig.writeArtCalibration(). 0/0/1 is "the model, untouched".
    private float artOffsetX;
    private float artOffsetY;
    private float artScale = 1f;
    private boolean calibrating;

    // Whether the tracked app is currently something other than what's on screen, so this window
    // should paint nothing. Re-evaluated on a slow timer rather than per frame: answering it
    // costs a query to UsageStatsManager (see ForegroundApp), and a second of lag when leaving
    // the music app is not worth paying for it 24 times a second.
    // Leaving the music app has to be answered quickly: the styles drawn on its cover drop to
    // their fallback the moment it goes, and a switch that trails a second behind the app it is
    // reacting to reads as the overlay being stuck rather than as it following. Three times a
    // second costs one cheap incremental query of the usage-event stream (see ForegroundApp).
    private static final long FOREGROUND_CHECK_MS = 300;
    /** The screen's size, and the "usage access" grant: neither changes on the scale above. */
    private static final long DISPLAY_CHECK_MS = 1_000;
    private static final long USAGE_ACCESS_CHECK_MS = 5_000;
    // The real display, and where this window sits on it. The cocoon is placed against the
    // *screen*, not against this view: the window is laid out with NO_LIMITS and into the display
    // cutout, so its own width/height and origin do not reliably correspond to the screen the
    // ART_* fractions were measured against — after the cutout change the ribbon drifted 170px
    // off the cover. Refreshed on the same slow timer as the foreground check.
    private float displayWidth;
    private float displayHeight;
    private final int[] viewLocation = new int[2];
    private final Rect displayBounds = new Rect();

    private boolean suppressed;
    /**
     * Whether that verdict has actually been reached yet, as opposed to merely defaulting to
     * "not hidden".
     *
     * Adding this window makes the framework measure, lay out and *draw* it within the same
     * message, while the tick loop that answers "is the music app in front?" only runs in the
     * next one. That first frame therefore went out under the field defaults — and since a track
     * change can pass through a pause, which stops the overlay and starts it again, the record
     * flashed over whatever app happened to be in front before the first check took it away.
     * Nothing is painted until there is a real answer; it arrives one frame later.
     */
    private boolean suppressionResolved;
    // Whether the tracked app was found to be the one on screen, and whether that could be
    // established at all — the two are different answers and are acted on differently.
    private boolean foregroundKnown;
    private boolean trackedAppOnScreen = true;
    private long lastForegroundCheckAtMs;
    private long lastDisplayCheckAtMs;
    private long lastUsageAccessCheckAtMs;

    EdgeGlowView(Context context) {
        super(context);
        density = context.getResources().getDisplayMetrics().density;
        paint.setStyle(Paint.Style.FILL);
        for (int i = 0; i < cocoonTiers.length; i++) cocoonTiers[i] = new Path();
    }

    /** Applied once at startup and again whenever the settings panel changes something while the
     *  overlay is already running (see OverlayEdgeGlowService's SharedPreferences listener). */
    void applyConfig(EdgeConfig.Snapshot config) {
        style = config.style;
        band = config.band;
        intensity = config.intensity;
        thicknessMul = config.thickness;
        brightnessMul = config.brightness;
        sensitivity = config.sensitivity;
        barSize = config.barSize;
        customPalette = config.customPalette;
        edgeTop = config.top;
        edgeBottom = config.bottom;
        edgeLeft = config.left;
        edgeRight = config.right;
        onlyOverMusicApp = config.onlyOverMusicApp;
        cocoonFallback = config.cocoonFallback;
        // Not applied while the handle is up: the drag in progress *is* the newer value, and the
        // preferences it would be re-read from are only written once that drag is finished.
        if (!calibrating) {
            artOffsetX = config.artOffsetX;
            artOffsetY = config.artOffsetY;
            artScale = config.artScale > 0 ? config.artScale : 1f;
        }
        // Answer again on the next tick rather than keep a verdict reached under the old setting
        // — the grant included, since this is also the path a freshly granted one arrives by.
        lastForegroundCheckAtMs = 0;
        lastUsageAccessCheckAtMs = 0;
    }

    void setPalette(int[][] palette) {
        fromPalette = currentAutoPalette();
        toPalette = palette != null ? palette : FALLBACK_PALETTE;
        paletteBlendStartMs = SystemClock.elapsedRealtime();
    }

    /**
     * The current track's own artwork, for "vinyl" — called once per track change from
     * OverlayEdgeGlowService, same call site as setPalette() above. The shader wrapping it is
     * built here rather than per frame, since it never needs to change until the next track
     * does; drawVinyl() only ever adjusts its matrix.
     */
    void setAlbumArt(Bitmap albumArt) {
        if (albumArt == null || albumArt.isRecycled()) {
            vinylBitmap = null;
            vinylShader = null;
            return;
        }
        vinylBitmap = albumArt;
        vinylShader = new BitmapShader(albumArt, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
    }

    /** Whether the track is actually playing right now — the one thing that gates "vinyl"'s
     *  rotation, called on every now-playing update rather than only on a track change. */
    void setPlaying(boolean playing) {
        vinylPlaying = playing;
    }

    /** The capture feeding this view stopped for good — drop straight back to ambient. */
    void clearLevels() {
        lastLevelsAtMs = 0;
        level = 0f;
        beatEnergy = 0f;
        lastBands = null;
    }

    /**
     * A real, honest impulse — a track change or a play/pause — the only kind ambient mode is
     * allowed to show. Called from OverlayEdgeGlowService. Harmless in live mode too: real beats
     * already drive beatEnergy just as strongly.
     */
    void pulse(float strength) {
        beatEnergy = Math.max(beatEnergy, clamp01(strength));
    }

    /**
     * Called from the capture engine's worker thread rather than the main one, and the view can
     * be re-fed by a new capture while an old one's last callback is still in flight.
     * Synchronized because bassHistory/bassCursor below are a plain
     * ring buffer with no other protection: two unsynchronized writers could tear a value or
     * lose an increment, corrupting the beat detector. The critical section is a fixed handful of
     * float operations on a 48-element array, called at most a few dozen times a second — never
     * enough contention for a lock to be worth avoiding.
     */
    synchronized void pushLevels(float[] bands) {
        if (bands == null || bands.length == 0) return;
        lastBands = bands;

        int bassBands = Math.min(BASS_END, bands.length);
        float bassSum = 0f;
        for (int i = 0; i < bassBands; i++) bassSum += bands[i];
        float newBass = bassBands > 0 ? bassSum / bassBands : 0f;

        float newLevel = averageForBand(bands);

        bassHistory[bassCursor] = newBass;
        bassCursor = (bassCursor + 1) % BEAT_HISTORY;
        float avg = 0f;
        for (float v : bassHistory) avg += v;
        avg /= BEAT_HISTORY;

        long now = SystemClock.elapsedRealtime();
        boolean isPeak = newBass > avg * 1.32f + 0.015f && newBass > 0.06f;
        if (isPeak && now - lastBeatAtMs > 190) {
            lastBeatAtMs = now;
            pendingBeat = true;
        }

        level = clamp01(newLevel * sensitivity);
        lastLevelsAtMs = now;
    }

    /** Averages whichever slice of the 32-band spectrum EdgeConfig's frequency choice selects —
     *  "full" (the whole spectrum, same as the full-screen visualizer) or one of bass/mid/treble
     *  for someone who wants the border to track a narrower range than the beat detector does. */
    private float averageForBand(float[] bands) {
        int from = bandFrom(bands.length);
        int to = bandTo(bands.length);
        if (to <= from) return 0f;
        float sum = 0f;
        for (int i = from; i < to; i++) sum += bands[i];
        return sum / (to - from);
    }

    /** First band of the [from, to) slice EdgeConfig's frequency choice selects — shared with
     *  drawBars(), so the panel's Fréquences setting narrows the spectrum in both styles rather
     *  than only in the glow's average. */
    private int bandFrom(int length) {
        if (EdgeConfig.BAND_MID.equals(band)) return Math.min(BASS_END, length);
        if (EdgeConfig.BAND_TREBLE.equals(band)) return Math.min(MID_END, length);
        return 0;
    }

    private int bandTo(int length) {
        if (EdgeConfig.BAND_BASS.equals(band)) return Math.min(BASS_END, length);
        if (EdgeConfig.BAND_MID.equals(band)) return Math.min(MID_END, length);
        return length;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        refreshDisplaySize();
        lastFrameMs = SystemClock.elapsedRealtime();
        handler.post(tick);
    }

    @Override
    protected void onDetachedFromWindow() {
        handler.removeCallbacks(tick);
        super.onDetachedFromWindow();
    }

    private void onTick() {
        // This and onDraw() below both run on the app's single main thread — the same one the
        // whole webview and every Activity run on. An exception escaping either of them would
        // crash the entire app, not just this decorative overlay, so both are defensive about
        // anything unexpected (a null palette entry, a transient view-detach race) rather than
        // ever letting that happen for the sake of a border glow.
        try {
            long now = SystemClock.elapsedRealtime();
            long dtMs = Math.max(0, Math.min(200, now - lastFrameMs));
            lastFrameMs = now;

            colorShift = (colorShift + dtMs / (float) COLOR_TRAVEL_MS * 3f) % 3f;

            if (pendingBeat) {
                pendingBeat = false;
                beatEnergy = 1f;
            }
            beatEnergy *= (float) Math.pow(0.9, dtMs / PULSE_DECAY_MS);

            ambientPhase += dtMs;
            advanceCocoonPhases(dtMs / 1000f);
            advanceBars(dtMs / 1000f);
            advanceVinyl(dtMs / 1000f);
            updateSuppression(now);

            invalidate();
        } catch (Exception e) {
            Log.w(TAG, "onTick", e);
        } finally {
            // Kept outside the try body so one bad tick doesn't also kill every tick after it.
            handler.postDelayed(tick, FRAME_INTERVAL_MS);
        }
    }

    /**
     * Winds the cocoon's phases on. Everything here speeds up with how loud the music actually
     * is — but only when the spectrum is real: without capture `drive` stays at zero and the
     * ribbon keeps its slow base rate, since a scene that surged and eased to a loudness it
     * cannot hear is the same lie as an invented beat. beatEnergy is allowed in either regime;
     * without capture it only ever rises on something that really happened.
     */
    private void advanceCocoonPhases(float dt) {
        boolean live = lastLevelsAtMs != 0
            && SystemClock.elapsedRealtime() - lastLevelsAtMs < LIVE_LEVELS_TIMEOUT_MS;
        float drive = live ? clamp01(level) : 0f;
        float beat = clamp01(beatEnergy);
        cocoonWaveA = wrapTwoPi(cocoonWaveA + dt * (0.85f + drive * 1.7f));
        cocoonWaveB = wrapTwoPi(cocoonWaveB + dt * (0.62f + drive * 1.15f));
        cocoonSweep = wrapTwoPi(cocoonSweep + dt * (0.38f + drive * 0.55f + beat * 0.6f));
        cocoonShear = wrapTwoPi(cocoonShear + dt * (0.42f + drive * 0.7f));
        cocoonOrbit = wrapTwoPi(cocoonOrbit + dt * 0.05f);
        cocoonTwinkle = wrapTwoPi(cocoonTwinkle + dt * 1.7f);
    }

    /** Turns "vinyl" at its fixed rate while playing; holds still, mid-turn, the moment it isn't
     *  — see VINYL_DEG_PER_SEC above for why this never speeds up with the music. */
    private void advanceVinyl(float dt) {
        if (!vinylPlaying) return;
        vinylAngleDeg = (vinylAngleDeg + dt * VINYL_DEG_PER_SEC) % 360f;
    }

    /** Eases the drawn spectrum towards the captured one and lets the peak caps fall. */
    private void advanceBars(float dt) {
        float[] bands = lastBands;
        boolean live = bands != null && bands.length > 0 && lastLevelsAtMs != 0
            && SystemClock.elapsedRealtime() - lastLevelsAtMs < LIVE_LEVELS_TIMEOUT_MS;
        int count = live ? bands.length : (barLevels != null ? barLevels.length : 0);
        if (count == 0) return;
        if (barLevels == null || barLevels.length != count) {
            barLevels = new float[count];
            barPeaks = new float[count];
            barPeakFall = new float[count];
        }
        // Frame-rate independent easing: the same visible attack and release whether this view is
        // managing its full ~24fps or dropping frames behind a busy foreground app.
        for (int i = 0; i < count; i++) {
            float target = live ? clamp01(bands[i]) : 0f;
            float rate = target > barLevels[i] ? 26f : 7f;
            barLevels[i] += (target - barLevels[i]) * Math.min(1f, rate * dt);
            if (barLevels[i] >= barPeaks[i]) {
                barPeaks[i] = barLevels[i];
                barPeakFall[i] = 0f;
            } else {
                barPeakFall[i] += dt * 0.9f;
                barPeaks[i] = Math.max(barLevels[i], barPeaks[i] - barPeakFall[i] * dt * 2.2f);
            }
        }
    }

    private static float wrapTwoPi(float value) {
        float tau = (float) (Math.PI * 2);
        float wrapped = value % tau;
        return wrapped < 0 ? wrapped + tau : wrapped;
    }

    /**
     * Decides whether this window should be showing anything at all right now. Only ever hides
     * on a definite answer — the setting is on, the permission is granted, and the app on screen
     * is something else; anything it cannot establish leaves the overlay visible, since a
     * decoration that silently refuses to appear is a far worse failure than one that appears
     * over an app it needn't have.
     */
    private void updateSuppression(long now) {
        // The screen's own size only changes on a fold or a rotation, and the "usage access"
        // grant almost never — neither is worth asking about at the rate the question "is the
        // music app still in front?" has to be asked to answer it promptly.
        if (now - lastDisplayCheckAtMs >= DISPLAY_CHECK_MS) {
            lastDisplayCheckAtMs = now;
            refreshDisplaySize();
        }
        if (lastForegroundCheckAtMs != 0 && now - lastForegroundCheckAtMs < FOREGROUND_CHECK_MS) return;
        lastForegroundCheckAtMs = now;
        Context context = getContext();
        if (lastUsageAccessCheckAtMs == 0 || now - lastUsageAccessCheckAtMs >= USAGE_ACCESS_CHECK_MS) {
            lastUsageAccessCheckAtMs = now;
            foregroundKnown = context != null && ForegroundApp.hasUsageAccess(context);
        }
        trackedAppOnScreen = !foregroundKnown || ForegroundApp.isTrackedAppInForeground(context);
        // Never while the calibration handle is up: the whole point of that moment is to see
        // where the anchor sits, and hiding it would leave the handle pointing at nothing.
        suppressed = !calibrating && onlyOverMusicApp && foregroundKnown && !trackedAppOnScreen;
        suppressionResolved = true;
    }

    /** Reads the screen's real size, which is what the cocoon is positioned against. */
    private void refreshDisplaySize() {
        try {
            WindowManager wm = (WindowManager) getContext().getSystemService(Context.WINDOW_SERVICE);
            if (wm == null) return;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                displayBounds.set(wm.getCurrentWindowMetrics().getBounds());
                displayWidth = displayBounds.width();
                displayHeight = displayBounds.height();
            } else {
                android.util.DisplayMetrics metrics = new android.util.DisplayMetrics();
                wm.getDefaultDisplay().getRealMetrics(metrics);
                displayWidth = metrics.widthPixels;
                displayHeight = metrics.heightPixels;
            }
        } catch (Exception e) {
            Log.w(TAG, "refreshDisplaySize", e);
        }
    }

    /**
     * Which style to actually paint. Only "cocoon" and "vinyl" are ever swapped: both are drawn
     * against where the music app's own album art sits (see the ART_* constants), so anywhere
     * but that app's now-playing screen either would be drawn against nothing at all. The other
     * two are tied to the screen edges and are just as true over anything.
     *
     * Left alone when the foreground app cannot be established — the same rule as suppression:
     * nothing is degraded on a guess.
     */
    private String activeStyle() {
        if (!EdgeConfig.STYLE_COCOON.equals(style) && !EdgeConfig.STYLE_VINYL.equals(style)) return style;
        if (!foregroundKnown || trackedAppOnScreen) return style;
        return EdgeConfig.STYLE_GLOW.equals(cocoonFallback) ? EdgeConfig.STYLE_GLOW : EdgeConfig.STYLE_BARS;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        try {
            // Drawing nothing empties this window's display list, so the overlay disappears
            // without the service having to be torn down and rebuilt every time someone glances
            // at another app. Also how the first frame stays blank until the question has been
            // put at all — see suppressionResolved.
            if (suppressed || !suppressionResolved) return;
            String active = activeStyle();
            if (EdgeConfig.STYLE_BARS.equals(active)) {
                drawBars(canvas);
            } else if (EdgeConfig.STYLE_COCOON.equals(active)) {
                drawCocoon(canvas);
            } else if (EdgeConfig.STYLE_VINYL.equals(active)) {
                drawVinyl(canvas);
            } else {
                drawGlow(canvas);
            }
            if (calibrating) drawArtFrame(canvas);
        } catch (Exception e) {
            Log.w(TAG, "onDraw", e);
        }
    }

    /**
     * Each of the 32 bands drawn on its own rather than reduced to the single scalar drawGlow()
     * works from. It began as a way to answer "is the data actually moving, or is the border just
     * not showing it" — an average can sit fairly still while the individual bands swing a lot —
     * and stayed the default because it shows what the capture delivers most directly.
     *
     * What it draws is no longer raw, though: the levels are eased (see advanceBars()), the row
     * is stroked through a gradient running along the edge so it sweeps the album's three accents
     * instead of being one flat colour, each bar is a rounded cap rather than a bare rectangle,
     * and a peak cap floats above each one and falls — the detail that shows how hard a band was
     * hit after the bar itself has dropped away.
     */
    private void drawBars(Canvas canvas) {
        int width = getWidth();
        int height = getHeight();
        if (width <= 0 || height <= 0) return;

        // Same liveness check drawGlow() falls back on: without it a capture that dies silently
        // would leave the last frame's bars lit on screen forever. Bars have no ambient regime to
        // fall back to — they show a spectrum or nothing, and inventing one is the thing this app
        // does not do — so "not live" means the eased levels run down to zero and stay there.
        boolean live = lastLevelsAtMs != 0
            && SystemClock.elapsedRealtime() - lastLevelsAtMs < LIVE_LEVELS_TIMEOUT_MS;
        float[] levels = barLevels;
        if (levels == null || levels.length == 0) return;
        if (!live) {
            boolean anythingLeft = false;
            for (float v : levels) {
                if (v > 0.004f) { anythingLeft = true; break; }
            }
            if (!anythingLeft) return;
        }

        int from = bandFrom(levels.length);
        int to = bandTo(levels.length);
        if (to <= from) return;

        paint.setStyle(Paint.Style.FILL);
        paint.setAntiAlias(true);
        float pulse = clamp01(beatEnergy);

        if (edgeBottom) drawBarRow(canvas, levels, from, to, width, height, false, pulse);
        if (edgeTop) drawBarRow(canvas, levels, from, to, width, height, true, pulse);
        if (edgeLeft) drawBarColumn(canvas, levels, from, to, width, height, false, pulse);
        if (edgeRight) drawBarColumn(canvas, levels, from, to, width, height, true, pulse);

        paint.setShader(null);
        paint.setAlpha(255);
        paint.setAntiAlias(false);
    }

    /** Peak length for one bar, honouring the size slider but never past the point where two
     *  opposite rows would collide across the middle of the app underneath. */
    private float barLimit(int extent) {
        float reference = Math.min(getWidth(), getHeight());
        return Math.min(reference * BAR_MAX_FRACTION * barSize, extent * 0.45f);
    }

    private float barLength(float bandLevel, int extent) {
        float value = clamp01(bandLevel * intensity * sensitivity);
        return Math.max(2f * density, value * barLimit(extent));
    }

    /** The gradient a whole row is drawn through: the three album accents laid along the edge, so
     *  the spectrum reads as one lit object instead of 32 identically-coloured sticks. */
    private Shader barSweep(float x0, float y0, float x1, float y1) {
        int[] colors = {
            withAlpha(saturate(paletteColorAt(0f)), 255),
            withAlpha(lit(paletteColorAt(0.6f), 0.55f), 255),
            withAlpha(saturate(ribbonColor(1.2f)), 255),
            withAlpha(lit(paletteColorAt(1.8f), 0.45f), 255),
            withAlpha(saturate(paletteColorAt(2.4f)), 255),
        };
        float[] stops = { 0f, 0.26f, 0.5f, 0.74f, 1f };
        return new LinearGradient(x0, y0, x1, y1, colors, stops, Shader.TileMode.CLAMP);
    }

    private int barAlpha(float bandLevel, float pulse) {
        float value = clamp01(bandLevel * intensity * sensitivity);
        return clamp255((int) ((70 + value * 125f + pulse * 35f) * brightnessMul));
    }

    /** One row of bars along a horizontal edge, growing inward from it. */
    private void drawBarRow(Canvas canvas, float[] levels, int from, int to, int width, int height,
                            boolean fromTop, float pulse) {
        int n = to - from;
        float slot = (float) width / n;
        float barWidth = slot * 0.7f;
        float radius = barWidth * 0.5f;
        paint.setShader(barSweep(0, 0, width, 0));
        for (int i = 0; i < n; i++) {
            float value = levels[from + i];
            float length = barLength(value, height);
            float left = i * slot + (slot - barWidth) * 0.5f;
            paint.setAlpha(barAlpha(value, pulse));
            canvas.drawRoundRect(
                left,
                fromTop ? -radius : height - length,
                left + barWidth,
                fromTop ? length : height + radius,
                radius, radius, paint
            );
            float peak = barPeaks[from + i];
            if (peak > value + 0.02f) {
                float peakAt = barLength(peak, height);
                paint.setAlpha(clamp255((int) ((150 + pulse * 60f) * brightnessMul)));
                canvas.drawRoundRect(
                    left,
                    fromTop ? peakAt : height - peakAt - barWidth * 0.34f,
                    left + barWidth,
                    fromTop ? peakAt + barWidth * 0.34f : height - peakAt,
                    radius, radius, paint
                );
            }
        }
    }

    /** One column of bars along a vertical edge, growing inward from it. */
    private void drawBarColumn(Canvas canvas, float[] levels, int from, int to, int width, int height,
                               boolean fromRight, float pulse) {
        int n = to - from;
        float slot = (float) height / n;
        float barWidth = slot * 0.7f;
        float radius = barWidth * 0.5f;
        paint.setShader(barSweep(0, 0, 0, height));
        for (int i = 0; i < n; i++) {
            float value = levels[from + i];
            float length = barLength(value, width);
            float top = i * slot + (slot - barWidth) * 0.5f;
            paint.setAlpha(barAlpha(value, pulse));
            canvas.drawRoundRect(
                fromRight ? width - length : -radius,
                top,
                fromRight ? width + radius : length,
                top + barWidth,
                radius, radius, paint
            );
            float peak = barPeaks[from + i];
            if (peak > value + 0.02f) {
                float peakAt = barLength(peak, width);
                paint.setAlpha(clamp255((int) ((150 + pulse * 60f) * brightnessMul)));
                canvas.drawRoundRect(
                    fromRight ? width - peakAt : peakAt,
                    top,
                    fromRight ? width - peakAt + barWidth * 0.34f : peakAt + barWidth * 0.34f,
                    top + barWidth,
                    radius, radius, paint
                );
            }
        }
    }


    private void drawGlow(Canvas canvas) {
        int width = getWidth();
        int height = getHeight();
        if (width <= 0 || height <= 0) return;

        // Reset from whatever drawCocoon() may have left the shared Paint in — style can change
        // live, mid-overlay, from a settings-panel edit. The alpha matters as much as the style
        // here: this method carries its own alpha inside the gradient's colours, so a leftover
        // per-strand alpha would silently scale the whole glow down.
        paint.setStyle(Paint.Style.FILL);
        paint.setAlpha(255);
        int color = displayColor();
        // beatEnergy carries either a real detected beat (live) or a real event's pulse() —
        // track change, play/pause — in both regimes.
        float pulse = clamp01(beatEnergy);
        float strength;
        boolean live = lastLevelsAtMs != 0
            && SystemClock.elapsedRealtime() - lastLevelsAtMs < LIVE_LEVELS_TIMEOUT_MS;
        if (live) {
            strength = clamp01(level);
        } else {
            // Symmetric breathing on three incommensurate waves — no beat, no invented tempo,
            // but fast and wide enough (7s/11s/17s, ±0.4 around a 0.5 mid-point) to actually read
            // as "alive" within a few seconds' glance, unlike the slower wash the full-screen
            // player uses for something meant to be watched continuously, not glanced at.
            double a = Math.sin(ambientPhase / 7_000.0 * Math.PI * 2);
            double b = Math.sin(ambientPhase / 11_000.0 * Math.PI * 2 + 1.7);
            double c = Math.sin(ambientPhase / 17_000.0 * Math.PI * 2 + 3.1);
            strength = (float) (0.5 + (a + b + c) / 3.0 * 0.4);
        }
        strength = clamp01(strength * intensity);

        // Bolder than a typical "glow" on purpose: this is meant to be noticeable at a glance
        // across a room, over whatever app is in front, not a subtle edge highlight — a first
        // pass at these constants read as too discreet on-device.
        float thicknessDp = (22f + strength * 70f + pulse * 40f) * thicknessMul;
        float thickness = Math.max(0f, thicknessDp) * density;
        int alpha = clamp255((int) ((190 + strength * 120f + pulse * 70f) * brightnessMul));

        int edgeColor = (color & 0x00FFFFFF) | (alpha << 24);
        int transparent = color & 0x00FFFFFF;

        if (edgeTop) drawEdge(canvas, 0, 0, width, thickness, edgeColor, transparent, true);
        if (edgeBottom) drawEdge(canvas, 0, height - thickness, width, thickness, edgeColor, transparent, false);
        if (edgeLeft) drawEdgeVertical(canvas, 0, 0, thickness, height, edgeColor, transparent, true);
        if (edgeRight) drawEdgeVertical(canvas, width - thickness, 0, thickness, height, edgeColor, transparent, false);
    }

    private void drawEdge(Canvas canvas, float left, float top, float w, float h, int from, int to, boolean fromTop) {
        float startY = fromTop ? top : top + h;
        float endY = fromTop ? top + h : top;
        paint.setShader(new LinearGradient(0, startY, 0, endY, from, to, Shader.TileMode.CLAMP));
        canvas.drawRect(left, top, left + w, top + h, paint);
    }

    private void drawEdgeVertical(Canvas canvas, float left, float top, float w, float h, int from, int to, boolean fromLeft) {
        float startX = fromLeft ? left : left + w;
        float endX = fromLeft ? left + w : left;
        paint.setShader(new LinearGradient(startX, 0, endX, 0, from, to, Shader.TileMode.CLAMP));
        canvas.drawRect(left, top, left + w, top + h, paint);
    }

    /** Where the music app's own album art sits, in this view's coordinates *and* on the screen —
     *  shared by "cocoon" and "vinyl", the two styles anchored to it rather than to the screen's
     *  edges. See the ART_* constants above for the model it comes from. */
    private static final class ArtRect {
        final float cx;
        final float cy;
        final float half;
        final float screenCx;
        final float screenCy;
        ArtRect(float cx, float cy, float half, float screenCx, float screenCy) {
            this.cx = cx;
            this.cy = cy;
            this.half = half;
            this.screenCx = screenCx;
            this.screenCy = screenCy;
        }
    }

    /**
     * Where this view's own coordinate space starts on the screen.
     *
     * The overlay is added MATCH_PARENT with FLAG_LAYOUT_NO_LIMITS and cutout mode ALWAYS, so its
     * canvas covers the whole display and view coordinates simply *are* screen coordinates.
     * getLocationOnScreen() does not always agree: on a Z Fold it reports the status bar's height
     * for a window that demonstrably starts at the very top of the screen, and subtracting that
     * pulled the disc 103 px above Deezer's cover — the exact height of that bar. The view's own
     * measured size settles the argument, since a view as tall as the display cannot begin
     * anywhere but at its top; only a genuinely inset window falls back to the reported location.
     */
    private void refreshOrigin() {
        getLocationOnScreen(viewLocation);
        if (displayWidth > 0 && getWidth() >= displayWidth - 1) viewLocation[0] = 0;
        if (displayHeight > 0 && getHeight() >= displayHeight - 1) viewLocation[1] = 0;
    }

    private ArtRect artRect() {
        // The screen's size is normally refreshed on the slow timer, but the anchor can be asked
        // for before the first of those has run — the calibration handle is put up in the same
        // breath as the overlay window itself, when this view has not even been measured yet, and
        // an anchor that answered "don't know" then left the handle parked in the top-left corner
        // of the screen. WindowManager can answer at any time, so ask it rather than give up.
        if (displayWidth <= 0 || displayHeight <= 0) refreshDisplaySize();
        // Landscape means the two-pane layout, portrait the one-column one — read every frame, so
        // folding the device moves the anchor with the cover.
        float screenW = displayWidth > 0 ? displayWidth : getWidth();
        float screenH = displayHeight > 0 ? displayHeight : getHeight();
        if (screenW <= 0 || screenH <= 0) return null;
        boolean wide = screenW > screenH;
        float side = wide
            ? Math.min(screenH * ART_WIDE_MAX_HEIGHT_FRACTION, screenW * 0.5f * ART_WIDE_MAX_PANE_FRACTION)
            : Math.min(screenW * ART_TALL_MAX_WIDTH_FRACTION, screenH * ART_TALL_MAX_HEIGHT_FRACTION);
        if (side <= 0) return null;

        float screenCx = screenW * (wide ? ART_WIDE_CENTER_X_FRACTION : ART_TALL_CENTER_X_FRACTION)
            + artOffsetX * screenW;
        float screenCy = (wide
            ? screenH * ART_WIDE_CENTER_Y_FRACTION
            : screenH * ART_TALL_TOP_FRACTION + side * 0.5f) + artOffsetY * screenH;
        // The calibration only resizes the disc about its own centre; where that centre sits is
        // the offsets' business alone, so a size correction never drags the anchor with it.
        float half = side * 0.5f * artScale;
        if (half <= 0) return null;

        refreshOrigin();
        return new ArtRect(screenCx - viewLocation[0], screenCy - viewLocation[1], half, screenCx, screenCy);
    }

    /** The screen's size, asked of WindowManager if the slow timer hasn't run yet — what the
     *  calibration handle is kept inside of. */
    float displayWidthPx() {
        if (displayWidth <= 0) refreshDisplaySize();
        return displayWidth > 0 ? displayWidth : getWidth();
    }

    float displayHeightPx() {
        if (displayHeight <= 0) refreshDisplaySize();
        return displayHeight > 0 ? displayHeight : getHeight();
    }

    /** The anchor as it stands, in screen coordinates: {centre x, centre y, half-size}. How
     *  ArtCalibrationPuck knows where to place itself when calibration starts. */
    boolean readArtAnchor(float[] out) {
        ArtRect art = artRect();
        if (art == null || out == null || out.length < 3) return false;
        out[0] = art.screenCx;
        out[1] = art.screenCy;
        out[2] = art.half;
        return true;
    }

    /**
     * Moves the anchor so that it lands on a point the user picked on screen, and remembers that
     * as an offset from the modelled position rather than as an absolute one — so the correction
     * still means something after a fold, a rotation or a resolution change.
     */
    void setArtCalibrationFromScreenCentre(float screenCx, float screenCy) {
        float screenW = displayWidth > 0 ? displayWidth : getWidth();
        float screenH = displayHeight > 0 ? displayHeight : getHeight();
        if (screenW <= 0 || screenH <= 0) return;
        float savedX = artOffsetX;
        float savedY = artOffsetY;
        artOffsetX = 0f;
        artOffsetY = 0f;
        ArtRect modelled = artRect();
        artOffsetX = savedX;
        artOffsetY = savedY;
        if (modelled == null) return;
        artOffsetX = clampOffset((screenCx - modelled.screenCx) / screenW);
        artOffsetY = clampOffset((screenCy - modelled.screenCy) / screenH);
    }

    /** Grows or shrinks the anchor about its own centre, for a cover the model sized wrong. */
    void nudgeArtScale(float factor) {
        artScale = Math.max(ART_SCALE_MIN, Math.min(ART_SCALE_MAX, artScale * factor));
    }

    float artOffsetX() {
        return artOffsetX;
    }

    float artOffsetY() {
        return artOffsetY;
    }

    float artScale() {
        return artScale;
    }

    /** Draws the anchor as a frame while the calibration handle is up, so the target is visible
     *  whichever style is selected — including the two that aren't drawn against the cover. */
    void setCalibrating(boolean calibrating) {
        this.calibrating = calibrating;
    }

    private static float clampOffset(float value) {
        if (Float.isNaN(value)) return 0f;
        return Math.max(-ART_OFFSET_LIMIT, Math.min(ART_OFFSET_LIMIT, value));
    }

    /**
     * The one style that isn't confined to the four screen edges: a ribbon of light wrapped
     * around where Deezer's own album art sits on screen — see ART_* above for where that
     * estimate comes from and its limits.
     *
     * The ribbon is one wave swept through the bundle: every strand rides the same lobed shape,
     * pushed a little further out and sheared a little further along in phase than the one
     * inside it. That shear is the whole trick — it fans the bundle open on one side of a lobe
     * and pinches it shut on the other. Hairlines, not thick bands: the light comes from how
     * many of them there are, never from any one being bright.
     *
     * Two details exist because this draws over *another app*, not over Vizuzik's own dark
     * screen, and Deezer tints its now-playing page from the cover — so the backdrop is
     * regularly pale. A diagonal gradient gives the bundle a bright crest instead of one flat
     * milky tone, and each strand is laid over a darker, wider copy of itself, the same reason
     * light UI text carries a shadow. Additive blending was tried first and is what a pale
     * backdrop defeats completely: screening light onto an already-bright green page changes
     * almost nothing.
     *
     * Reuses the beat/palette/ambient state the other two styles maintain; the geometry, the
     * sweep and the per-point spectrum sampling are what's specific here.
     */
    private void drawCocoon(Canvas canvas) {
        int width = getWidth();
        int height = getHeight();
        if (width <= 0 || height <= 0) return;

        ArtRect art = artRect();
        if (art == null) return;
        float cx = art.cx;
        float cy = art.cy;
        float half = art.half;
        // artRect() already refreshed viewLocation; screenW/screenH are only needed here, for
        // how much room the bundle has to breathe into before it runs off the nearest edge.
        float screenW = displayWidth > 0 ? displayWidth : width;
        float screenH = displayHeight > 0 ? displayHeight : height;

        boolean live = lastLevelsAtMs != 0
            && SystemClock.elapsedRealtime() - lastLevelsAtMs < LIVE_LEVELS_TIMEOUT_MS;
        float[] bands = live ? lastBands : null;
        float loud = live ? clamp01(level) : 0.22f;
        // Only real captured audio may drive the motion: without it the ribbon keeps its slow
        // base rate rather than surging to a loudness it cannot hear.
        float drive = live ? loud : 0f;
        float pulse = clamp01(beatEnergy);

        // The whole bundle breathes: it widens on loud passages and flares on an impulse, which
        // is most of what makes it read as alive rather than as a decal.
        float band = Math.max(
            half * 0.08f,
            Math.min(half * COCOON_BAND * thicknessMul * (1 + drive * 0.30f + pulse * 0.22f), half * 0.55f)
        );
        float swing = half * COCOON_SWING * (1 + drive * 0.25f);

        // How much room there is between the cover and the nearest screen edge. The unfolded
        // layout leaves the cover barely a tenth of the width from the left one, and a bundle
        // that just ran off it would read as cut in half. Allowed slightly past the edge rather
        // than squeezed to a thread, since a ribbon grazing the border still looks deliberate.
        float room = Math.min(
            Math.min(art.screenCx, screenW - art.screenCx),
            Math.min(art.screenCy, screenH - art.screenCy)
        ) * 1.06f - half * COCOON_INNER;
        if (room > 0 && band + swing > room) {
            float squeeze = room / (band + swing);
            band *= squeeze;
            swing *= squeeze;
        }

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setAntiAlias(true);

        float lineWidth = Math.max(1f, COCOON_LINE * density * thicknessMul);
        int alpha = clamp255((int) ((205 + loud * 40f + pulse * 50f) * brightnessMul));
        // Oscillating rather than fixed: the weave visibly opens and closes instead of holding
        // one shape while only the light moves over it.
        float shearSpan = COCOON_SHEAR + 0.55f * (float) Math.sin(cocoonShear);

        // Built once and reused across all three passes: the alpha inside them is the envelope,
        // and Paint's own alpha scales the whole shader, so per-strand and per-pass brightness
        // needs no second gradient.
        Shader sweep = buildCocoonSweep(cx, cy, half, false);
        Shader shade = buildCocoonSweep(cx, cy, half, true);

        for (int i = 0; i <= COCOON_SPOKES; i++) {
            cocoonLevels[i] = (sampleLevel(bands, (float) i / COCOON_SPOKES) * 0.30f + pulse * 0.10f)
                * intensity;
        }

        float waveScale = 1f / (2 * COCOON_WAVE_MAX);
        for (Path tier : cocoonTiers) tier.reset();

        for (int s = 0; s < COCOON_STRANDS; s++) {
            float u = (float) s / (COCOON_STRANDS - 1); // 0 against the artwork .. 1 outermost
            float shear = u * shearSpan;
            float inner = half * COCOON_INNER;
            float reach = half * (0.25f + u * 0.5f);
            float offset = band * u;

            // sin(ka + phase) split by the angle-sum identity, so the per-point work is six
            // multiply-adds against the tables instead of three sines.
            float p1 = cocoonWaveA + shear;
            float p2 = -cocoonWaveB * 1.3f + shear * 1.7f;
            float p3 = -cocoonWaveB * 0.7f - shear;
            float s1 = (float) Math.sin(p1), c1 = (float) Math.cos(p1);
            float s2 = (float) Math.sin(p2), c2 = (float) Math.cos(p2);
            float s3 = (float) Math.sin(p3), c3 = (float) Math.cos(p3);

            Path path = cocoonTiers[s * COCOON_TIERS / COCOON_STRANDS];
            for (int i = 0; i <= COCOON_SPOKES; i++) {
                float wave = (COCOON_SIN3[i] * c1 + COCOON_COS3[i] * s1) * 0.085f
                    + (COCOON_SIN5[i] * c2 + COCOON_COS5[i] * s2) * 0.042f
                    + (COCOON_SIN2[i] * c3 + COCOON_COS2[i] * s3) * 0.055f;
                float radius = inner * COCOON_SHAPE[i]
                    + offset
                    + swing * ((wave + COCOON_WAVE_MAX) * waveScale)
                    + cocoonLevels[i] * reach;
                float px = cx + COCOON_COS[i] * radius;
                float py = cy + COCOON_SIN[i] * radius;
                if (i == 0) path.moveTo(px, py);
                else path.lineTo(px, py);
            }
            path.close();
        }

        // A dark outline under each strand, following the same envelope as the light: what keeps
        // pale lines legible over a pale page, and Deezer's page is tinted from the very artwork
        // the palette came from, so pale is the normal case.
        paint.setShader(shade);
        paint.setStrokeWidth(lineWidth * 1.8f);
        for (int t = 0; t < COCOON_TIERS; t++) {
            float u = (float) t / (COCOON_TIERS - 1);
            paint.setAlpha(clamp255((int) (COCOON_SHADOW_ALPHA * (1 - 0.55f * u))));
            canvas.drawPath(cocoonTiers[t], paint);
        }

        // Then the strands themselves, dense against the cover and dissolving outward. There is
        // no separate bloom pass any more: restroking the bundle wide and faint was most of the
        // cost of a frame — some 25 million antialiased shaded pixels a second — and a border
        // effect that stutters is worse than one that does not glow.
        paint.setShader(sweep);
        paint.setStrokeWidth(lineWidth);
        for (int t = 0; t < COCOON_TIERS; t++) {
            float u = (float) t / (COCOON_TIERS - 1);
            paint.setAlpha(clamp255((int) (alpha * (1 - 0.6f * u))));
            canvas.drawPath(cocoonTiers[t], paint);
        }

        paint.setShader(null);
        drawCocoonSparks(canvas, cx, cy, half, band + swing, loud, pulse);
        paint.setAntiAlias(false);
    }

    /**
     * The sweep every strand is stroked with — and, in its darker form, outlined with. Two things
     * are happening in it.
     *
     * The colours run rich and saturated through the troughs and near-white at the crests. The
     * palette comes from the album art and Deezer tints its own page from that same art, so
     * straight out of the palette the ribbon is very nearly the colour of what is behind it;
     * pushing the colours away from grey, and the crests towards white, gives it contrast that
     * doesn't depend on the hue being different.
     *
     * And the alpha varies far more than the colour does: most of the ribbon sits at a fifth of
     * full opacity and two slim arcs blaze. Light reads as light when it is concentrated —
     * holding the whole band at one middle value is what made the first attempt look like fog.
     * Turning slowly, so the crests travel around the weave.
     */
    /** The ribbon's take on a palette colour: turned off the album's hue so it cannot vanish
     *  into Deezer's page, unless the user has pinned their own colours. */
    private int ribbonColor(float index) {
        int base = paletteColorAt(index);
        return customPalette != null ? base : rotateHue(base, COCOON_HUE_TURN);
    }

    private Shader buildCocoonSweep(float cx, float cy, float half, boolean dark) {
        float angle = cocoonSweep;
        float reach = half * 1.6f;
        float dx = (float) Math.cos(angle) * reach;
        float dy = (float) Math.sin(angle) * reach;
        // Raised off the floor since the first version was too faint to make out on a device:
        // still a strong crest-to-trough range, but nothing falls away to almost nothing.
        float[] envelope = { 0.34f, 0.55f, 1f, 0.68f, 0.46f, 0.92f, 0.56f, 0.34f };
        float[] stops = { 0f, 0.16f, 0.30f, 0.44f, 0.58f, 0.74f, 0.88f, 1f };
        int[] tones = dark
            ? new int[] { 0, 0, 0, 0, 0, 0, 0, 0 }
            : new int[] {
                saturate(dim(ribbonColor(1.0f), 0.55f)),
                saturate(ribbonColor(1.2f)),
                lit(saturate(ribbonColor(1.5f)), 0.72f),
                saturate(ribbonColor(1.8f)),
                saturate(dim(ribbonColor(2.0f), 0.7f)),
                lit(saturate(ribbonColor(2.3f)), 0.6f),
                saturate(ribbonColor(2.6f)),
                saturate(dim(ribbonColor(2.9f), 0.55f)),
            };
        int[] colors = new int[tones.length];
        for (int i = 0; i < tones.length; i++) {
            colors[i] = withAlpha(tones[i], Math.round(envelope[i] * 255));
        }
        return new LinearGradient(cx - dx, cy - dy, cx + dx, cy + dy, colors, stops, Shader.TileMode.CLAMP);
    }

    /** Motes drifting in the ribbon, each on its own soft halo. Deterministic from the index
     *  alone — no per-frame state to keep, and the field stays put across a fold instead of
     *  reshuffling. */
    private void drawCocoonSparks(Canvas canvas, float cx, float cy, float half, float bandWidth,
                                  float loud, float pulse) {
        paint.setStyle(Paint.Style.FILL);
        paint.setAlpha(255);
        for (int i = 0; i < 16; i++) {
            float seed = i * 2.399963f; // golden angle: spreads them without a visible pattern
            float angle = seed + cocoonOrbit * (1f + (i % 5) * 0.24f);
            float radius = half * squircle(angle) * COCOON_INNER
                + bandWidth * (0.15f + 0.9f * frac01((float) Math.sin(seed * 12.9898f) * 43758.547f));
            float twinkle = 0.35f + 0.65f * (float) Math.abs(Math.sin(cocoonTwinkle + seed));
            float px = cx + (float) Math.cos(angle) * radius;
            float py = cy + (float) Math.sin(angle) * radius;
            float size = (1f + (i % 3) * 0.55f) * density * (0.7f + loud);
            paint.setColor(withAlpha(
                lit(saturate(ribbonColor(i % 3)), 0.35f),
                clamp255((int) ((30 + loud * 40f) * twinkle * brightnessMul))
            ));
            canvas.drawCircle(px, py, size * 3f, paint);
            paint.setColor(withAlpha(
                lit(saturate(ribbonColor(i % 3)), 0.7f),
                clamp255((int) ((90 + loud * 90f + pulse * 60f) * twinkle * brightnessMul))
            ));
            canvas.drawCircle(px, py, size, paint);
        }
    }

    /**
     * "Vinyl": the track's own artwork, redrawn as a spinning record exactly where the music
     * app's now-playing screen keeps its album art (the same estimate "cocoon" is drawn against
     * — see artRect()/the ART_* constants). Deezer's own artwork underneath never moves; painting
     * a full, opaque circular copy of it on top and turning that copy is what actually makes it
     * read as spinning, the way a physical record does, rather than a decoration around a still
     * image.
     *
     * Nothing is drawn before the first track's artwork arrives (see setAlbumArt()) — there is no
     * placeholder shape, since a blank turntable would be a stranger thing to show than nothing.
     */
    private void drawVinyl(Canvas canvas) {
        Bitmap bitmap = vinylBitmap;
        BitmapShader shader = vinylShader;
        if (bitmap == null || shader == null || bitmap.isRecycled()) return;

        ArtRect art = artRect();
        if (art == null) return;
        float half = art.half;
        buildVinylShaders(half);

        canvas.save();
        canvas.translate(art.cx, art.cy);
        // The shadow is cast by the record, not turned by it, so it goes down before the rotation
        // — and outside it, since what shows of it is the ring past the record's own edge.
        vinylPaint.reset();
        vinylPaint.setAntiAlias(true);
        vinylPaint.setStyle(Paint.Style.FILL);
        vinylPaint.setShader(vinylShadow);
        canvas.drawCircle(0, 0, half * VINYL_SHADOW_REACH, vinylPaint);

        canvas.rotate(vinylAngleDeg);
        // A small beat-driven lift, same spirit as the web player's own disc scaling up on an
        // impulse — the one bit of this style that answers the music rather than just turning at
        // its own fixed rate.
        float lift = 1f + clamp01(beatEnergy) * 0.02f;
        canvas.scale(lift, lift);

        // An opaque disc under the artwork before anything else. Nothing behind this window may
        // show through the record — a cover with an alpha channel, or one that doesn't quite fill
        // the circle, would otherwise let Deezer's own still artwork ghost through the turning
        // one, which reads as a double exposure rather than as a record.
        vinylPaint.setShader(null);
        vinylPaint.setColor(withAlpha(VINYL_VOID_COLOR, 255));
        canvas.drawCircle(0, 0, half, vinylPaint);

        // The artwork itself, scaled to cover a circle of radius `half` — the shorter of its two
        // sides fills the disc exactly, the longer one overflows and is cropped by drawCircle()
        // never painting past that radius, the same crop "cover" sizing gives a square image.
        float scale = 2f * half / Math.min(bitmap.getWidth(), bitmap.getHeight());
        vinylMatrix.setScale(scale, scale);
        vinylMatrix.postTranslate(-bitmap.getWidth() * scale * 0.5f, -bitmap.getHeight() * scale * 0.5f);
        shader.setLocalMatrix(vinylMatrix);
        vinylPaint.setAlpha(255);
        vinylPaint.setShader(shader);
        canvas.drawCircle(0, 0, half, vinylPaint);

        // Darkened towards the rim: a flat circle of artwork looks like a sticker, and the app's
        // own still cover is right underneath it to be mistaken for.
        vinylPaint.setShader(vinylVignette);
        canvas.drawCircle(0, 0, half, vinylPaint);
        vinylPaint.setShader(null);

        drawVinylGrooves(canvas, half);
        drawVinylLabel(canvas, half);
        drawVinylRim(canvas, half);

        canvas.restore();
    }

    /** The shadow and the vignette depend on nothing but the disc's size, so they are rebuilt
     *  only when that changes — a fold, a calibration, not every frame. */
    private void buildVinylShaders(float half) {
        if (vinylShadow != null && vinylVignette != null && Math.abs(half - vinylShadersForHalf) < 0.5f) {
            return;
        }
        vinylShadersForHalf = half;
        vinylVignette = new RadialGradient(
            0, 0, half,
            new int[] { withAlpha(Color.BLACK, 0), withAlpha(Color.BLACK, 0), withAlpha(Color.BLACK, 46), withAlpha(Color.BLACK, 130) },
            new float[] { 0f, 0.55f, 0.86f, 1f },
            Shader.TileMode.CLAMP
        );
        vinylShadow = new RadialGradient(
            0, 0, half * VINYL_SHADOW_REACH,
            new int[] { withAlpha(Color.BLACK, 120), withAlpha(Color.BLACK, 120), withAlpha(Color.BLACK, 0) },
            new float[] { 0f, 1f / VINYL_SHADOW_REACH, 1f },
            Shader.TileMode.CLAMP
        );
    }

    /** Faint concentric rings over the artwork, the same repeating-radial-gradient texture the
     *  web player's .disc__grooves gives its own spinning record. */
    private void drawVinylGrooves(Canvas canvas, float half) {
        vinylPaint.setShader(null);
        vinylPaint.setStyle(Paint.Style.STROKE);
        float start = half * VINYL_GROOVE_START;
        float span = half * VINYL_GROOVE_SPAN;
        for (int i = 0; i < VINYL_GROOVES; i++) {
            float radius = start + span * ((i + 1f) / VINYL_GROOVES);
            vinylPaint.setStrokeWidth(Math.max(1f, density * 0.6f));
            vinylPaint.setColor(withAlpha(Color.BLACK, 70));
            canvas.drawCircle(0, 0, radius, vinylPaint);
            vinylPaint.setStrokeWidth(Math.max(0.6f, density * 0.35f));
            vinylPaint.setColor(withAlpha(Color.WHITE, 26));
            canvas.drawCircle(0, 0, radius - density * 0.8f, vinylPaint);
        }
    }

    /** The centre label and spindle hole — what turns a circle of artwork into a record rather
     *  than a coaster. Coloured from the same travelling palette as the rest of the overlay.
     *
     *  Every colour here carries its own alpha and the paint's is reset first: sharing one Paint
     *  with the grooves above is what once left this drawn at their alpha of 26, i.e. all but
     *  invisible, which is exactly what made the whole record look like a transparency.
     */
    private void drawVinylLabel(Canvas canvas, float half) {
        float labelRadius = half * VINYL_LABEL_FRACTION;
        int[] colors = {
            withAlpha(VINYL_VOID_COLOR, 255),
            withAlpha(VINYL_VOID_COLOR, 255),
            withAlpha(saturate(paletteColorAt(0f)), 255),
            withAlpha(saturate(paletteColorAt(1f)), 255),
            withAlpha(dim(paletteColorAt(1f), 0.35f), 255),
        };
        float[] stops = { 0f, 0.30f, 0.42f, 0.86f, 1f };
        vinylPaint.setStyle(Paint.Style.FILL);
        vinylPaint.setAlpha(255);
        vinylPaint.setShader(new RadialGradient(0, 0, labelRadius, colors, stops, Shader.TileMode.CLAMP));
        canvas.drawCircle(0, 0, labelRadius, vinylPaint);
        vinylPaint.setShader(null);
        vinylPaint.setColor(withAlpha(Color.BLACK, 255));
        canvas.drawCircle(0, 0, Math.max(1.5f * density, labelRadius * 0.16f), vinylPaint);
    }

    /**
     * The square the anchor currently claims the album art occupies, drawn only while the
     * calibration handle is up. Deliberately a square and not a disc: what is being lined up is
     * the *cover*, and its corners are the part you can actually judge against the app underneath
     * — a circle inside a square leaves nothing to align. Drawn whatever the selected style, so
     * the anchor can also be set from "bars" or "glow" before switching over to a style that
     * needs it.
     */
    private void drawArtFrame(Canvas canvas) {
        ArtRect art = artRect();
        if (art == null) return;
        float half = art.half;
        vinylPaint.reset();
        vinylPaint.setAntiAlias(true);
        vinylPaint.setStyle(Paint.Style.STROKE);
        vinylPaint.setStrokeWidth(Math.max(2f, density * 1.6f));
        vinylPaint.setColor(withAlpha(Color.BLACK, 150));
        canvas.drawRect(art.cx - half, art.cy - half, art.cx + half, art.cy + half, vinylPaint);
        vinylPaint.setStrokeWidth(Math.max(1f, density * 0.9f));
        vinylPaint.setColor(withAlpha(lit(saturate(paletteColorAt(0f)), 0.55f), 255));
        canvas.drawRect(art.cx - half, art.cy - half, art.cx + half, art.cy + half, vinylPaint);
        // A cross through the middle: aligning two centres is easier than aligning four edges.
        float arm = half * 0.16f;
        canvas.drawLine(art.cx - arm, art.cy, art.cx + arm, art.cy, vinylPaint);
        canvas.drawLine(art.cx, art.cy - arm, art.cx, art.cy + arm, vinylPaint);
    }

    /** The record's edge: a dark rim with a thin lit ring just inside it. Cheap, and the single
     *  detail that most makes the disc sit *on* the app's own cover instead of in it. */
    private void drawVinylRim(Canvas canvas, float half) {
        vinylPaint.setShader(null);
        vinylPaint.setStyle(Paint.Style.STROKE);
        float rim = Math.max(1.5f, density * 1.4f);
        vinylPaint.setStrokeWidth(rim);
        vinylPaint.setColor(withAlpha(Color.BLACK, 165));
        canvas.drawCircle(0, 0, half - rim * 0.5f, vinylPaint);
        vinylPaint.setStrokeWidth(Math.max(0.8f, density * 0.5f));
        vinylPaint.setColor(withAlpha(Color.WHITE, 38));
        canvas.drawCircle(0, 0, half - rim * 1.8f, vinylPaint);
    }

    private static float squircle(float angle) {
        double c = Math.abs(Math.cos(angle));
        double s = Math.abs(Math.sin(angle));
        double d = Math.pow(Math.pow(c, COCOON_SQUIRCLE) + Math.pow(s, COCOON_SQUIRCLE), 1.0 / COCOON_SQUIRCLE);
        return d <= 0 ? 1f : (float) (1.0 / d);
    }

    /** Lifts a colour towards white. The ribbon has to stay legible over a pale app background,
     *  and its crests read as light rather than as paint. */
    private static int lit(int color, float amount) {
        int r = Color.red(color);
        int g = Color.green(color);
        int b = Color.blue(color);
        return Color.rgb(
            Math.round(r + (255 - r) * amount),
            Math.round(g + (255 - g) * amount),
            Math.round(b + (255 - b) * amount)
        );
    }

    /** Turns a colour's hue by `degrees`, keeping how light and how colourful it is. */
    private static int rotateHue(int color, float degrees) {
        float r = Color.red(color) / 255f;
        float g = Color.green(color) / 255f;
        float b = Color.blue(color) / 255f;
        float max = Math.max(r, Math.max(g, b));
        float min = Math.min(r, Math.min(g, b));
        float delta = max - min;
        if (delta <= 0.0001f) return color; // grey has no hue to turn

        float hue;
        if (max == r) hue = ((g - b) / delta) % 6f;
        else if (max == g) hue = (b - r) / delta + 2f;
        else hue = (r - g) / delta + 4f;
        hue = (hue * 60f + degrees) % 360f;
        if (hue < 0) hue += 360f;

        float c = delta;
        float x = c * (1 - Math.abs((hue / 60f) % 2f - 1));
        float m = min;
        float rr, gg, bb;
        if (hue < 60) { rr = c; gg = x; bb = 0; }
        else if (hue < 120) { rr = x; gg = c; bb = 0; }
        else if (hue < 180) { rr = 0; gg = c; bb = x; }
        else if (hue < 240) { rr = 0; gg = x; bb = c; }
        else if (hue < 300) { rr = x; gg = 0; bb = c; }
        else { rr = c; gg = 0; bb = x; }
        return Color.rgb(
            clamp255(Math.round((rr + m) * 255)),
            clamp255(Math.round((gg + m) * 255)),
            clamp255(Math.round((bb + m) * 255))
        );
    }

    /** Multiplies a colour's distance from grey. Deezer tints its now-playing page from the same
     *  artwork the palette is extracted from, so an unmodified palette colour lands very close to
     *  whatever is behind the ribbon. */
    private static int saturate(int color) {
        int r = Color.red(color);
        int g = Color.green(color);
        int b = Color.blue(color);
        float mean = (r + g + b) / 3f;
        return Color.rgb(
            clamp255(Math.round(mean + (r - mean) * COCOON_SATURATION)),
            clamp255(Math.round(mean + (g - mean) * COCOON_SATURATION)),
            clamp255(Math.round(mean + (b - mean) * COCOON_SATURATION))
        );
    }

    /** Darkens a colour towards black — the troughs between the ribbon's lit crests. */
    private static int dim(int color, float amount) {
        return Color.rgb(
            clamp255(Math.round(Color.red(color) * amount)),
            clamp255(Math.round(Color.green(color) * amount)),
            clamp255(Math.round(Color.blue(color) * amount))
        );
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (clamp255(alpha) << 24);
    }

    /** Fractional part, always positive — the mote hash relies on it to spread them. */
    private static float frac01(float value) {
        float f = value - (float) Math.floor(value);
        return Float.isNaN(f) ? 0f : f;
    }

    /**
     * Spectrum level for one point around the ribbon, mirrored the same way the full-screen
     * player's _mirroredBand() is: bass in the middle of the ring, treble at the seam. Without
     * live bands (capture not granted, or momentarily stalled) this falls back to the same
     * three-incommensurate-wave breathing drawGlow() uses in ambient mode, phase-shifted by
     * position around the ring so it reads as a slow current rather than one uniform pulse — a
     * gentle "still alive" without ever claiming to have heard a beat it didn't.
     */
    private float sampleLevel(float[] bands, float frac) {
        if (bands != null && bands.length > 0) {
            float d = Math.abs(frac - 0.5f) * 2f;
            int idx = Math.round(d * (bands.length - 1));
            idx = Math.max(0, Math.min(bands.length - 1, idx));
            return clamp01(bands[idx] * sensitivity);
        }
        double a = Math.sin((ambientPhase + frac * 4000) / 7_000.0 * Math.PI * 2);
        double b = Math.sin((ambientPhase + frac * 6000) / 11_000.0 * Math.PI * 2 + 1.7);
        double c = Math.sin((ambientPhase + frac * 3000) / 17_000.0 * Math.PI * 2 + 3.1);
        return clamp01((float) (0.15 + (a + b + c) / 3.0 * 0.12));
    }

    private int displayColor() {
        return paletteColorAt(0f);
    }

    /**
     * Palette colour at a floating index, offset by the same ambient colour travel displayColor()
     * rides on — lets several elements each sit at their own fixed offset into the travelling
     * palette instead of all showing the exact same colour at once. drawCocoon()'s three strands
     * use this at 0/1/2 so they read as distinct threads rather than one flat ring.
     */
    private int paletteColorAt(float floatIndex) {
        int[][] palette = currentPalette();
        float p = floatIndex + colorShift;
        int base = (int) Math.floor(p);
        int index = ((base % 3) + 3) % 3;
        int next = (index + 1) % 3;
        float frac = p - (float) Math.floor(p);
        int[] a = palette[index];
        int[] b = palette[next];
        int r = Math.round(a[0] + (b[0] - a[0]) * frac);
        int g = Math.round(a[1] + (b[1] - a[1]) * frac);
        int bl = Math.round(a[2] + (b[2] - a[2]) * frac);
        return Color.rgb(clamp255(r), clamp255(g), clamp255(bl));
    }

    /** Custom palette (fixed, user-chosen colors) bypasses the cover-driven blend entirely — see
     *  currentAutoPalette() for the "auto" behavior this replaces. */
    private int[][] currentPalette() {
        return customPalette != null ? customPalette : currentAutoPalette();
    }

    /** Blends fromPalette toward toPalette over PALETTE_BLEND_MS — see setPalette(). */
    private int[][] currentAutoPalette() {
        long elapsed = SystemClock.elapsedRealtime() - paletteBlendStartMs;
        if (elapsed >= PALETTE_BLEND_MS) return toPalette;
        float t = Math.max(0f, elapsed / (float) PALETTE_BLEND_MS);
        int[][] blended = new int[3][3];
        for (int i = 0; i < 3; i++) {
            int[] from = fromPalette[i];
            int[] to = toPalette[i];
            for (int c = 0; c < 3; c++) {
                blended[i][c] = Math.round(from[c] + (to[c] - from[c]) * t);
            }
        }
        return blended;
    }

    private static float clamp01(float value) {
        if (Float.isNaN(value)) return 0f;
        return value < 0 ? 0 : Math.min(value, 1);
    }

    private static int clamp255(int value) {
        return value < 0 ? 0 : Math.min(value, 255);
    }
}
