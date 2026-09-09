package com.vizuzik.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
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

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

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
 * Five rendering styles, picked in the settings panel (EdgeConfig): "bars", the default, drawing
 * each of the 32 bands on its own; "glow", the border that averages them into one scalar;
 * "particles", sparks spawned from the edges by whichever bands just moved; "cocoon", a ribbon
 * woven around Deezer's own album art; and "vinyl", that same artwork redrawn as a spinning record
 * in the same spot — see drawCocoon() and drawVinyl() below for why and how those two aren't
 * edge-only like the first three.
 *
 * A sixth, "cassette" (see drawCassette()), is never offered in that same settings panel: it has
 * no album art to redraw and no Deezer layout to anchor itself against, so it only ever appears
 * as one of the three choices (alongside "bars" and "vinyl") on LockScreenVisualizerActivity's own
 * screen — see setStandalone()/setStandaloneStyle() and LockScreenVisualizerPreference.
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

    // "Particles": a fixed pool rather than a growing list, so a burst of spawns can never
    // allocate — a spawn that finds every slot already alive is simply skipped for that frame.
    // 48 is enough sparks on screen at once to read as a field rather than a scatter of dots
    // without ever being dense enough to obscure the app underneath.
    private static final int PARTICLE_COUNT = 48;
    private static final float PARTICLE_LIFE_MS = 850f;
    // Chance per lane per second of spawning one spark at full band level — scaled down by how
    // loud that band actually is, so a quiet passage spawns far fewer than a loud one instead of
    // firing at a constant rate regardless of what's playing.
    private static final float PARTICLE_SPAWN_RATE = 5.5f;
    private static final int PARTICLE_LANES_PER_EDGE = 8;
    private static final float PARTICLE_SPEED_DP = 90f;
    private static final float PARTICLE_DRAG = 0.985f;

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
    // How much of the screen's shorter side "vinyl" covers when standalone, centred — see
    // artRect()'s standalone branch. Well under 1: the lock screen visualizer is upfront about
    // costing real battery for a fully-driven display (see the ADR), and the one thing this view
    // can still do about that is keep most of the screen actually black rather than lit, the way
    // "bars"/"glow" already do just by being confined to a thin edge. "cassette" does not use this
    // — see drawCassette(), which fills the whole screen edge to edge like the web player's own
    // .cassette rather than a centred icon.
    private static final float STANDALONE_ART_FRACTION = 0.62f;
    // The same two rates the web player's .cassette__reel/.cassette__reel--b use — see
    // drawCassette(). Kept as two so the reels visibly drift out of phase with each other, the way
    // tape actually winds from one to the other, rather than turning as a single locked unit.
    private static final float CASSETTE_DEG_PER_SEC_A = 360f / 3.2f;
    private static final float CASSETTE_DEG_PER_SEC_B = 360f / 3.8f;
    // The web version's viewBox is 320x200 (see index.html's .cassette__art) — every coordinate
    // in drawCassette() is lifted straight from it, so this is the one constant that maps those
    // units onto however big artRect() says the shell should be here.
    private static final float CASSETTE_VIEWBOX_WIDTH = 320f;
    private static final float CASSETTE_VIEWBOX_HEIGHT = 200f;
    // Same --void CSS variable the web player's own .cover background sits on (#14141f).
    private static final int VINYL_VOID_COLOR = 0xFF14141F;
    /** How far past the record's own edge its shadow reaches, as a multiple of the radius. */
    private static final float VINYL_SHADOW_REACH = 1.09f;
    // How much bigger than the record's own radius the shrunk window has to be, as a multiple —
    // it has to hold the shadow (VINYL_SHADOW_REACH), the beat-driven lift in drawVinyl() (up to
    // 2%), and the rim's own stroke width, with a little left over rather than clipping any of
    // them exactly at the edge.
    private static final float VINYL_WINDOW_MARGIN = 1.18f;

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
    // "cassette"'s tape curve — a fixed shape, moved into place by the canvas transform in
    // drawCassette() rather than rebuilt — allocated once for the same reason cocoonTiers is
    // below: this view redraws up to 30 times a second, and a fresh Path every frame for a curve
    // that never actually changes shape would be pure waste.
    private final Path cassetteTapePath = new Path();
    // The brand tab's own rounded-rect clip, for its three colour bands — see drawCassette().
    // Cached the same way and for the same reason as cassetteTapePath just above: a fixed shape,
    // built once rather than reallocated on every one of this view's ~30 redraws a second.
    private final Path cassetteBrandClipPath = new Path();
    // "cassette"'s shading shaders — built once, lazily, on the first frame that draws it, and
    // never rebuilt afterward: unlike buildVinylShaders() (which depends on the disc's own size
    // in *screen* pixels and so has to track that size), every one of these lives entirely inside
    // drawCassette()'s already-scaled canvas (see the canvas.scale() call there), in the same
    // fixed 320x200 viewBox units every other coordinate in that method uses — coordinates that
    // never change no matter how big the standalone screen actually is.
    private Shader cassetteCaseLightShader;
    private Shader cassetteCaseVignetteShader;
    // Centred on (0,0): drawCassetteReel() translates to each reel's own centre before using
    // these, so one pair of shaders serves both reels rather than one pair each.
    private Shader cassetteReelDiscShader;
    private Shader cassetteReelShadowShader;
    // Muted the same way the web version's own .cassette__art-image is (saturate(0.85)
    // contrast(0.93) brightness(0.96)): a glossy phone photo shown at full brightness/saturation
    // on a printed cassette label would look pasted on rather than printed. Built once alongside
    // the shaders above, reused on the vinylPaint whenever the label draws the album art.
    private ColorMatrixColorFilter cassetteArtColorFilter;
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
    // "cassette"'s own two reel angles — advanced the same way as vinylAngleDeg, gated by the same
    // vinylPlaying flag (there is only ever one style's audio-reactive state actually playing at
    // once, whichever the standalone screen is currently drawing). See advanceCassette().
    private float cassetteReelADeg;
    private float cassetteReelBDeg;
    // How far into the track playback actually is, for "cassette"'s own wound-tape coils — see
    // setCassetteProgress()/cassetteProgress(). Anchored rather than tracked per-frame, the same
    // reasoning as the web player's own PlaybackProgress.positionNow(): a position is only ever
    // known as of the instant it was reported, so the anchor is extrapolated forward by real
    // elapsed time between updates rather than re-queried every tick.
    private long cassetteDurationMs;
    private long cassetteAnchorPositionMs;
    private long cassetteAnchorAtMs;
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

    /** One spark of the "particles" style. Plain mutable fields on a fixed-size pool (see
     *  PARTICLE_COUNT) rather than a list: allocating/removing per spawn/death would mean garbage
     *  on a decorative overlay's own frame tick, which this file avoids everywhere else too. */
    private static final class Particle {
        boolean alive;
        float x, y, vx, vy;
        float ageMs;
        float size;
        /** Fixed at spawn so a spark keeps one identity as the travelling palette moves under it —
         *  same trick drawCocoon()'s three strands use at indices 0/1/2. */
        float colorSlot;
    }

    private final Particle[] particles = new Particle[PARTICLE_COUNT];
    private final java.util.Random particleRandom = new java.util.Random();

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
    // Apps to hide over unconditionally — see EdgeConfig.Snapshot.hiddenPackages and
    // updateSuppression(). Independent of onlyOverMusicApp: that one narrows the overlay down to
    // a single app, this one carves specific apps back out of however wide it is otherwise set.
    private Set<String> hiddenPackages = Collections.emptySet();
    // Restricts "cocoon"/"vinyl" past "the tracked app is in front" to "and it's showing its own
    // full-screen player" — see activeStyle() and DeezerPlayerAccessibilityService. Meaningless,
    // and never acted on, unless that service is actually connected: NowPlayerScreenState.
    private boolean requirePlayerScreen;
    // This phone's own correction to the modelled album-art anchor — see artRect() and
    // EdgeConfig.writeArtCalibration(). Keyed by EdgeConfig.formatLayoutKey() — the screen's exact
    // current width x height — rather than by a fixed number of named layouts: a Fold's closed
    // and open configurations can share an aspect ratio (both read as "landscape" to artRect()'s
    // own wide/tall test) while being physically nothing alike, and a correction dragged into
    // place in one is not a correction for the other. A missing entry means "the model,
    // untouched" (0/0/1) — see calibrationFor() and currentLayoutKey().
    private Map<String, float[]> artCalibrations = new HashMap<>();
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

    // Set once by LockScreenVisualizerActivity, never by OverlayEdgeGlowService: this view also
    // hosts the "fake AOD" full-screen visualizer, where there is no other app underneath to
    // check for or protect the touches of, and no Deezer layout for "cocoon"/"vinyl" to anchor
    // themselves against — see updateSuppression() and activeStyle() for what each skips because
    // of it.
    private boolean standalone;

    void setStandalone(boolean value) {
        standalone = value;
    }

    // Set once by LockScreenVisualizerActivity right after setStandalone(true), from its own,
    // shorter style list (LockScreenVisualizerPreference.STYLE_*) — deliberately never fed from
    // EdgeConfig.style/applyConfig() the way every other field on this view is, since the two
    // pickers are meant to stay independent (see that class and activeStyle() below). Null until
    // set, which activeStyle() treats as "use the general Edge Visualizer logic instead" — the
    // brief window between this view being attached and LockScreenVisualizerActivity.onStart()
    // actually calling setStandaloneStyle().
    private String standaloneStyle;

    void setStandaloneStyle(String value) {
        standaloneStyle = value;
    }

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
    /** Stricter than trackedAppOnScreen: true only when the tracked app being in front rests on
     *  real evidence, not on ForegroundApp's own fail-open default — see activeStyle(), the one
     *  place that needs this distinction rather than trackedAppOnScreen's permissive answer. */
    private boolean trackedAppConfirmed;
    private long lastForegroundCheckAtMs;
    private long lastDisplayCheckAtMs;
    private long lastUsageAccessCheckAtMs;

    EdgeGlowView(Context context) {
        super(context);
        density = context.getResources().getDisplayMetrics().density;
        paint.setStyle(Paint.Style.FILL);
        for (int i = 0; i < cocoonTiers.length; i++) cocoonTiers[i] = new Path();
        for (int i = 0; i < particles.length; i++) particles[i] = new Particle();
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
        hiddenPackages = config.hiddenPackages != null ? config.hiddenPackages : Collections.emptySet();
        requirePlayerScreen = config.requirePlayerScreen;
        // Not applied while the handle is up: the drag in progress *is* the newer value, and the
        // preferences it would be re-read from are only written once that drag is finished. A
        // fresh copy, never the Snapshot's own map: a drag mutates entries in place (see
        // calibrationFor()), and the Snapshot handed to every other listener of the same
        // SharedPreferences change must not see edits that haven't been written back yet.
        if (!calibrating) {
            artCalibrations = new HashMap<>(config.artCalibrations);
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

    /**
     * "cassette"'s own wound-tape coils track playback the same way the web player's progress
     * bar does — see LockScreenVisualizerActivity.onNowPlayingChanged(), which calls this
     * alongside setPlaying() on every now-playing update, not just a track change (a position
     * this stale by even a few seconds would make the coils visibly jump on the next redraw).
     */
    void setCassetteProgress(long positionMs, long durationMs) {
        cassetteDurationMs = Math.max(0, durationMs);
        long clampedPosition = Math.max(0, positionMs);
        cassetteAnchorPositionMs = cassetteDurationMs > 0
            ? Math.min(clampedPosition, cassetteDurationMs)
            : clampedPosition;
        cassetteAnchorAtMs = SystemClock.elapsedRealtime();
    }

    /** 0..1, extrapolated from the last setCassetteProgress() anchor by real elapsed time —
     *  frozen the instant playback isn't, same rule advanceVinyl()/advanceCassette() apply to
     *  their own rotation. 0 (nothing wound onto the take-up side yet) if no duration is known
     *  yet, which is also what the coils show before the first now-playing update ever arrives. */
    private float cassetteProgress() {
        if (cassetteDurationMs <= 0) return 0f;
        long elapsed = vinylPlaying ? SystemClock.elapsedRealtime() - cassetteAnchorAtMs : 0;
        long position = Math.max(0, Math.min(cassetteDurationMs, cassetteAnchorPositionMs + elapsed));
        return position / (float) cassetteDurationMs;
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
            advanceCassette(dtMs / 1000f);
            advanceParticles(dtMs / 1000f);
            updateSuppression(now);
            updateWindowBounds();
            // OverlayDiagnostics is a single static, process-wide surface for the settings panel's
            // diagnostics block — meant to describe OverlayEdgeGlowService's own window. A
            // standalone instance (LockScreenVisualizerActivity) publishing into the same fields
            // would fight with it rather than add anything the panel knows how to show.
            if (!standalone) publishDiagnostics();

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

    /** Turns "cassette"'s two reels at their fixed, slightly different rates while playing; holds
     *  both still, mid-turn, the instant it isn't — same rule as advanceVinyl() above. */
    private void advanceCassette(float dt) {
        if (!vinylPlaying) return;
        cassetteReelADeg = (cassetteReelADeg + dt * CASSETTE_DEG_PER_SEC_A) % 360f;
        cassetteReelBDeg = (cassetteReelBDeg + dt * CASSETTE_DEG_PER_SEC_B) % 360f;
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

    /**
     * Ages every spark, moves the live ones, and spawns fresh ones off whichever active edge's
     * bands just moved. Spawning only ever happens with real levels flowing in — same rule as
     * advanceBars(): a spectrum style has no ambient regime to fall back to, since inventing
     * sparks for a spectrum it isn't hearing is exactly the kind of invented rhythm this app
     * never shows. Existing sparks still age out normally once the capture goes quiet.
     */
    private void advanceParticles(float dt) {
        for (Particle p : particles) {
            if (!p.alive) continue;
            p.ageMs += dt * 1000f;
            if (p.ageMs >= PARTICLE_LIFE_MS) {
                p.alive = false;
                continue;
            }
            p.vx *= PARTICLE_DRAG;
            p.vy *= PARTICLE_DRAG;
            p.x += p.vx * dt;
            p.y += p.vy * dt;
        }

        float[] bands = lastBands;
        boolean live = bands != null && bands.length > 0 && lastLevelsAtMs != 0
            && SystemClock.elapsedRealtime() - lastLevelsAtMs < LIVE_LEVELS_TIMEOUT_MS;
        if (!live) return;

        int width = getWidth();
        int height = getHeight();
        if (width <= 0 || height <= 0) return;

        int from = bandFrom(bands.length);
        int to = bandTo(bands.length);
        if (to <= from) return;
        float pulse = clamp01(beatEnergy);

        if (edgeTop) spawnParticlesOnEdge(bands, from, to, width, height, 0, pulse);
        if (edgeBottom) spawnParticlesOnEdge(bands, from, to, width, height, 1, pulse);
        if (edgeLeft) spawnParticlesOnEdge(bands, from, to, width, height, 2, pulse);
        if (edgeRight) spawnParticlesOnEdge(bands, from, to, width, height, 3, pulse);
    }

    /** One edge's lanes, each an evenly-spaced sample of the active band range — mirrored the same
     *  way drawBars() mirrors its own slots, bass in the middle of the edge rather than piled at
     *  one end of every one of the four. edgeSide: 0 top, 1 bottom, 2 left, 3 right. */
    private void spawnParticlesOnEdge(float[] bands, int from, int to, int width, int height,
                                      int edgeSide, float pulse) {
        int n = to - from;
        boolean horizontal = edgeSide < 2;
        int extent = horizontal ? width : height;
        for (int lane = 0; lane < PARTICLE_LANES_PER_EDGE; lane++) {
            int band = mirroredBandIndex(lane * n / PARTICLE_LANES_PER_EDGE, n, from);
            float value = clamp01(bands[band] * sensitivity);
            float chance = (value * intensity + pulse * 0.4f) * PARTICLE_SPAWN_RATE / PARTICLE_LANES_PER_EDGE;
            if (chance <= 0f || particleRandom.nextFloat() > chance) continue;

            Particle p = freeParticle();
            if (p == null) return; // pool full — try again next tick rather than force one out

            float along = (lane + 0.5f) / PARTICLE_LANES_PER_EDGE * extent;
            float speed = (PARTICLE_SPEED_DP * density) * (0.5f + value);
            switch (edgeSide) {
                case 0: p.x = along; p.y = 0; p.vx = 0; p.vy = speed; break;
                case 1: p.x = along; p.y = height; p.vx = 0; p.vy = -speed; break;
                case 2: p.x = 0; p.y = along; p.vx = speed; p.vy = 0; break;
                default: p.x = width; p.y = along; p.vx = -speed; p.vy = 0; break;
            }
            p.ageMs = 0f;
            p.size = (2.2f + value * 3.4f) * density;
            p.colorSlot = (float) band / Math.max(1, bands.length - 1) * 3f;
            p.alive = true;
        }
    }

    private Particle freeParticle() {
        for (Particle p : particles) {
            if (!p.alive) return p;
        }
        return null;
    }

    /** Each spark drawn as a small bright core over a wider, dimmer halo — the same two-pass idea
     *  drawCocoonSparks() uses for its own motes — fading out linearly over its short life. */
    private void drawParticles(Canvas canvas) {
        int width = getWidth();
        int height = getHeight();
        if (width <= 0 || height <= 0) return;

        paint.setStyle(Paint.Style.FILL);
        paint.setShader(null);
        paint.setAntiAlias(true);

        for (Particle p : particles) {
            if (!p.alive) continue;
            float lifeFrac = clamp01(p.ageMs / PARTICLE_LIFE_MS);
            float fade = 1f - lifeFrac;
            int color = saturate(paletteColorAt(p.colorSlot));

            paint.setColor(withAlpha(lit(color, 0.3f), clamp255((int) (60 * fade * brightnessMul))));
            canvas.drawCircle(p.x, p.y, p.size * 2.4f, paint);
            paint.setColor(withAlpha(lit(color, 0.6f), clamp255((int) (220 * fade * brightnessMul))));
            canvas.drawCircle(p.x, p.y, p.size, paint);
        }

        paint.setAntiAlias(false);
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
        // Standalone (LockScreenVisualizerActivity) has no other app underneath to check for or
        // hide from — it *is* the whole screen, with nothing else in the window stack this view
        // could be suppressed in favour of. Every question below is meaningless there.
        if (standalone) {
            suppressed = false;
            suppressionResolved = true;
            if (getVisibility() != VISIBLE) setVisibility(VISIBLE);
            return;
        }
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
        boolean trackedInForeground = foregroundKnown && ForegroundApp.isTrackedAppInForeground(context);
        trackedAppOnScreen = !foregroundKnown || trackedInForeground;
        // isTrackedAppInForeground() fails open (answers "yes" when it simply doesn't know), which
        // is right for suppression above but wrong for activeStyle() below — see
        // ForegroundApp.isLastAnswerConfirmed() and the "vinyl"/"cocoon" comment there.
        trackedAppConfirmed = trackedInForeground && ForegroundApp.isLastAnswerConfirmed();
        // Only asked when there is a list to check against — most installs pick nothing, and the
        // question costs the same incremental usage-event query onlyOverMusicApp's already does,
        // just wasted if there is nothing here for its answer to matter to.
        // Read whether or not there is a hide list to check it against: the diagnostics panel's
        // whole job is to say which app the overlay thinks is in front, and an answer that only
        // exists when a setting happens to be on would be missing exactly when it's needed.
        // currentPackage() is the same 300ms-throttled latch isTrackedAppInForeground() just used,
        // so asking it again here costs a field read.
        String foreground = foregroundKnown ? ForegroundApp.currentForegroundPackage(context) : null;
        lastForegroundPackage = foreground != null ? foreground : "";
        boolean explicitlyHidden = foregroundKnown && !hiddenPackages.isEmpty()
            && hiddenPackages.contains(foreground);
        // Never while the calibration handle is up: the whole point of that moment is to see
        // where the anchor sits, and hiding it would leave the handle pointing at nothing.
        suppressed = !calibrating
            && ((onlyOverMusicApp && foregroundKnown && !trackedAppOnScreen) || explicitlyHidden);
        suppressionResolved = true;
        // Drawing nothing is not the same as not being there. From Android 12 the mere presence
        // of this window over another app costs that app its touches unless the window is faint
        // enough (see addOverlayView) or its root view is actually INVISIBLE — an empty display
        // list is neither. While there is nothing to show, this window steps out of the way
        // properly rather than hovering, unfelt but not unnoticed, over whatever is underneath.
        int wanted = suppressed ? INVISIBLE : VISIBLE;
        if (getVisibility() != wanted) setVisibility(wanted);
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
     * These two need the music app to be *known* to be in front, not merely not known to be
     * absent — the opposite of the rule suppression follows, and for a reason. Hiding on a guess
     * costs a decoration; drawing a record on a guess puts an opaque disc over an app it was
     * never measured for, hiding that app's own content and, from Android 12, stopping its
     * touches from being delivered at all. So without an answer they fall back to an edge style,
     * which is true over anything. That answer needs "usage access", which is what the settings
     * panel says these two styles are for — and it is trackedAppConfirmed, not trackedAppOnScreen,
     * that actually carries it: the latter also reads true whenever ForegroundApp simply hasn't
     * observed a foreground app yet (its own deliberate fail-open, correct for suppression, wrong
     * here), which is how the disc was seen spinning over an app that was neither Deezer nor
     * anything measured for it. See ForegroundApp.isLastAnswerConfirmed().
     *
     * requirePlayerScreen narrows it a step further, past "the tracked app is in front" to "and
     * it's showing its own full-screen player" — Deezer can be in front while showing search, its
     * home tab, or a playlist, with a track still playing behind a docked mini-player, and both
     * of these styles are measured against where the *full* player keeps its cover, not any of
     * those. Same rule as the app-level check: acted on only once NowPlayerScreenState actually
     * has an answer (its service connected), never on a guess in either direction.
     */
    private String activeStyle() {
        // The lock screen's own style choice, set once by LockScreenVisualizerActivity — see
        // setStandaloneStyle(). Answered before anything below because it comes from a completely
        // separate picker (LockScreenVisualizerPreference, not EdgeConfig.style): "vinyl" there
        // means the screen-centred rendering artRect()'s standalone branch gives it, never the
        // Deezer-anchored one the overlay uses, so it must never fall through to the cocoon/vinyl
        // fallback logic below, which answers a different question ("is Deezer's own cover on
        // screen right now") that standalone has no way to ask.
        if (standalone && standaloneStyle != null) return standaloneStyle;
        if (!EdgeConfig.STYLE_COCOON.equals(style) && !EdgeConfig.STYLE_VINYL.equals(style)) return style;
        // Standalone has no tracked app's now-playing screen to model the artwork's position
        // against in the first place (see the ART_* constants) — there is no layout to have
        // measured, only Vizuzik's own plain background. Always the fallback, same one the
        // overlay uses whenever the tracked app isn't what's on screen. Only reachable here for
        // "cocoon": a standalone view whose own style is "vinyl" already returned above, from a
        // picker that never offers "cocoon" in the first place — see LockScreenVisualizerPreference.
        if (standalone) {
            return EdgeConfig.STYLE_GLOW.equals(cocoonFallback) ? EdgeConfig.STYLE_GLOW : EdgeConfig.STYLE_BARS;
        }
        boolean playerScreenKnown = requirePlayerScreen && NowPlayerScreenState.isServiceConnected();
        boolean onPlayerScreen = !playerScreenKnown || NowPlayerScreenState.isOnPlayerScreen();
        if (foregroundKnown && trackedAppConfirmed && onPlayerScreen) return style;
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
            } else if (EdgeConfig.STYLE_PARTICLES.equals(active)) {
                drawParticles(canvas);
            } else if (EdgeConfig.STYLE_COCOON.equals(active)) {
                drawCocoon(canvas);
            } else if (EdgeConfig.STYLE_VINYL.equals(active)) {
                drawVinyl(canvas);
            } else if (EdgeConfig.STYLE_CASSETTE.equals(active)) {
                drawCassette(canvas);
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
     * What it draws is no longer raw, though: the levels are eased (see advanceBars()), the band
     * order along each row/column is mirrored so bass sits in the middle and treble at both ends
     * (see mirroredBandIndex()) rather than piling the loudest band at one fixed end of every
     * edge, the row is stroked through a gradient running along the edge so it sweeps the album's
     * three accents instead of being one flat colour, each bar is a rounded cap rather than a
     * bare rectangle, and a peak cap floats above each one and falls — the detail that shows how
     * hard a band was hit after the bar itself has dropped away.
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

    /**
     * Maps a slot along a row/column (0..n-1) onto a band within [from, from+n), bass in the
     * middle and treble at both ends — the same mirroring src/visualizer.js's _mirroredBand()
     * uses for its own "bars" style. Walking the spectrum in raw order instead put band 0 (bass,
     * reliably the loudest band in real music) at a fixed end — slot 0, the left of every row and
     * the top of every column — so that end was always the tallest, on every edge, on every
     * track: a structural lean, not a coincidence. Mirroring it spreads the same energy evenly
     * across both halves of each edge instead of piling it on one side.
     */
    private static int mirroredBandIndex(int i, int n, int from) {
        if (n <= 1) return from;
        float mid = (n - 1) * 0.5f;
        float d = Math.abs(i - mid) / mid;
        return from + Math.round(d * (n - 1));
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
            int band = mirroredBandIndex(i, n, from);
            float value = levels[band];
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
            float peak = barPeaks[band];
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
            int band = mirroredBandIndex(i, n, from);
            float value = levels[band];
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
            float peak = barPeaks[band];
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

        // No Deezer layout to model here (see the ART_* constants' own comment above) — this view
        // *is* the whole screen, so "vinyl" just centres itself on it, sized off the shorter side so
        // it never runs close to an edge in either orientation. No calibration to apply either:
        // that corrects the *model* below for a phone it estimated wrong, and there is no model
        // here to be wrong about. "cassette" does not read this branch — see drawCassette(), which
        // covers the whole screen instead of a centred icon.
        if (standalone) {
            float half = Math.min(screenW, screenH) * STANDALONE_ART_FRACTION * 0.5f;
            if (half <= 0) return null;
            refreshOrigin();
            float screenCx = screenW * 0.5f;
            float screenCy = screenH * 0.5f;
            return new ArtRect(screenCx - viewLocation[0], screenCy - viewLocation[1], half, screenCx, screenCy);
        }

        boolean wide = screenW > screenH;
        float side = wide
            ? Math.min(screenH * ART_WIDE_MAX_HEIGHT_FRACTION, screenW * 0.5f * ART_WIDE_MAX_PANE_FRACTION)
            : Math.min(screenW * ART_TALL_MAX_WIDTH_FRACTION, screenH * ART_TALL_MAX_HEIGHT_FRACTION);
        if (side <= 0) return null;

        // This exact screen size's own correction — see the field comment on artCalibrations for
        // why it's keyed this finely rather than just by wide/tall. Read-only lookup: unlike
        // calibrationFor(), never creates an entry, so merely rendering never plants a stray
        // default calibration for every size the phone happens to pass through.
        float[] calibration = artCalibrations.get(EdgeConfig.formatLayoutKey(screenW, screenH));
        float offsetX = calibration != null ? calibration[0] : 0f;
        float offsetY = calibration != null ? calibration[1] : 0f;
        float scale = calibration != null ? calibration[2] : 1f;

        float screenCx = screenW * (wide ? ART_WIDE_CENTER_X_FRACTION : ART_TALL_CENTER_X_FRACTION)
            + offsetX * screenW;
        float screenCy = (wide
            ? screenH * ART_WIDE_CENTER_Y_FRACTION
            : screenH * ART_TALL_TOP_FRACTION + side * 0.5f) + offsetY * screenH;
        // The calibration only resizes the disc about its own centre; where that centre sits is
        // the offsets' business alone, so a size correction never drags the anchor with it.
        float half = side * 0.5f * scale;
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
     * still means something after the same screen size recurs later (a rotation back, a fold
     * back). It does *not* survive a fold or a rotation *while it's happening*, on purpose: those
     * change the screen's exact size, which is what the calibration is keyed by (see
     * artCalibrations and currentLayoutKey()), so this always corrects whichever size is on
     * screen right now and leaves every other size's own calibration exactly as it was.
     */
    void setArtCalibrationFromScreenCentre(float screenCx, float screenCy) {
        float screenW = displayWidth > 0 ? displayWidth : getWidth();
        float screenH = displayHeight > 0 ? displayHeight : getHeight();
        if (screenW <= 0 || screenH <= 0) return;
        float[] entry = calibrationFor(currentLayoutKey());
        float savedX = entry[0];
        float savedY = entry[1];
        entry[0] = 0f;
        entry[1] = 0f;
        ArtRect modelled = artRect();
        entry[0] = savedX;
        entry[1] = savedY;
        if (modelled == null) return;
        entry[0] = clampOffset((screenCx - modelled.screenCx) / screenW);
        entry[1] = clampOffset((screenCy - modelled.screenCy) / screenH);
    }

    /** Grows or shrinks the anchor about its own centre, for a cover the model sized wrong — only
     *  ever the exact screen size currently on screen, same as setArtCalibrationFromScreenCentre()
     *  above. */
    void nudgeArtScale(float factor) {
        float[] entry = calibrationFor(currentLayoutKey());
        entry[2] = Math.max(ART_SCALE_MIN, Math.min(ART_SCALE_MAX, entry[2] * factor));
    }

    /** EdgeConfig.formatLayoutKey() for the screen size currently on screen — same width/height
     *  artRect() itself reads. Exposed so OverlayEdgeGlowService knows which stored calibration a
     *  finished drag belongs to. */
    String currentLayoutKey() {
        float screenW = displayWidth > 0 ? displayWidth : getWidth();
        float screenH = displayHeight > 0 ? displayHeight : getHeight();
        return EdgeConfig.formatLayoutKey(screenW, screenH);
    }

    /** The stored calibration for one screen size, creating a fresh 0/0/1 entry in artCalibrations
     *  if this is the first correction ever made at that size. Only ever called while an actual
     *  drag/nudge is in progress — artRect()'s own per-frame lookup stays read-only (see there) so
     *  merely rendering never plants a stray entry for every size the phone passes through. The
     *  returned array is live: mutating it in place is how a drag is recorded, with nothing
     *  written back to EdgeConfig until OverlayEdgeGlowService.finishCalibration() reads it. */
    private float[] calibrationFor(String layoutKey) {
        float[] entry = artCalibrations.get(layoutKey);
        if (entry == null) {
            entry = new float[] { 0f, 0f, 1f };
            artCalibrations.put(layoutKey, entry);
        }
        return entry;
    }

    /** The calibration for whichever screen size is currently on screen — see currentLayoutKey().
     *  What OverlayEdgeGlowService.finishCalibration() writes back to EdgeConfig, one size at a
     *  time, once a drag ends. */
    float artOffsetX() {
        return calibrationFor(currentLayoutKey())[0];
    }

    float artOffsetY() {
        return calibrationFor(currentLayoutKey())[1];
    }

    float artScale() {
        return calibrationFor(currentLayoutKey())[2];
    }

    /** Draws the anchor as a frame while the calibration handle is up, so the target is visible
     *  whichever style is selected — including the two that aren't drawn against the cover. */
    void setCalibrating(boolean calibrating) {
        this.calibrating = calibrating;
    }

    /**
     * Where OverlayEdgeGlowService learns that "vinyl" needs a different window than everything
     * else — see the field comment on windowBoundsListener for why.
     */
    interface WindowBoundsListener {
        /**
         * small=false: the window should cover the whole screen, at whatever touch-safe alpha the
         * service itself caps it to — what every other style needs.
         *
         * small=true: the window should shrink to a square of side 2*outerHalf centred on
         * (screenCx, screenCy), still at that same touch-safe alpha — see VINYL_WINDOW_MARGIN for
         * why that square has to be bigger than the record it holds, and OverlayEdgeGlowService's
         * touchSafeAlpha for why the shrink doesn't buy this window any more opacity than the
         * others: it still sits over the exact spot Deezer's own skip-track swipe is performed.
         */
        void onWindowBoundsWanted(boolean small, float screenCx, float screenCy, float outerHalf);
    }

    private WindowBoundsListener windowBoundsListener;
    // The bounds last reported to that listener, so a tick that changes nothing about them never
    // asks WindowManager to redo a layout it already has — a resize this view cannot see land
    // (unlike a draw, which just shows up on the next frame) is worth asking for only when it
    // would actually move something.
    private boolean lastWantedSmall;
    private float lastWantedCx = Float.NaN;
    private float lastWantedCy = Float.NaN;
    private float lastWantedHalf = Float.NaN;

    void setWindowBoundsListener(WindowBoundsListener listener) {
        windowBoundsListener = listener;
    }

    /**
     * Forces the next tick's updateWindowBounds() to re-apply the window's bounds even if they
     * land on the exact same target as before, instead of taking the "nothing to do" shortcut —
     * called on every track change (see OverlayEdgeGlowService.onNowPlayingChanged()).
     *
     * That shortcut compares only against the target *last asked for*, never against where the
     * window actually, verifiably is. A window that starts out a few pixels short of the modelled
     * anchor — a display-metrics read caught mid-settle right as this window was first added, the
     * one moment nothing here waits for — computes an equally wrong target and then matches it
     * forever after: every later tick derives the same modelled position from the same stable
     * inputs, lands within closeEnough()'s 2px tolerance of that first, wrong value, and is
     * therefore never re-sent to WindowManager. Reopening Deezer "fixes" it today only because
     * that tears the whole service down and rebuilds the window from nothing. A track change is a
     * far cheaper, far more frequent moment to give the anchor the same fresh start.
     */
    void invalidateWindowBounds() {
        lastWantedCx = Float.NaN;
        lastWantedCy = Float.NaN;
        lastWantedHalf = Float.NaN;
    }

    /**
     * The one place "vinyl" and every other style actually disagree about what this window
     * should be. Bars/glow paint along the four screen edges — a large area, but one Deezer
     * doesn't put much of its own touch handling in. "Vinyl" sits squarely on the cover, exactly
     * where Deezer's own left/right swipe (skip to the previous/next track) is performed, so it
     * is the one style that cannot trade any touch pass-through away, however small the window —
     * a swiped finger doesn't care that the window under it only covers the record.
     *
     * Shrinking the window down to just the disc, for as long as vinyl is actually what's being
     * drawn, still helps: it keeps every screen pixel *outside* the record exactly as touchable
     * as it always was, rather than the touch-safe alpha cap sitting over the whole screen for no
     * reason while this style is active. What it does not buy is a different alpha for the disc
     * itself — that stays capped at touchSafeAlpha in OverlayEdgeGlowService, same as everywhere
     * else, so the swipe still reaches Deezer. The disc reads as very slightly translucent for it,
     * which is the one trade this app is willing to make over a gesture Deezer users rely on.
     */
    private void updateWindowBounds() {
        if (windowBoundsListener == null) return;
        ArtRect art = calibrating || suppressed || !EdgeConfig.STYLE_VINYL.equals(activeStyle())
            ? null
            : artRect();
        boolean small = art != null;
        float cx = small ? art.screenCx : 0f;
        float cy = small ? art.screenCy : 0f;
        float half = small ? art.half * VINYL_WINDOW_MARGIN : 0f;

        boolean unchanged = small == lastWantedSmall
            && (!small || (closeEnough(cx, lastWantedCx) && closeEnough(cy, lastWantedCy)
                && closeEnough(half, lastWantedHalf)));
        if (unchanged) return;

        lastWantedSmall = small;
        lastWantedCx = cx;
        lastWantedCy = cy;
        lastWantedHalf = half;
        windowBoundsListener.onWindowBoundsWanted(small, cx, cy, half);
    }

    /** The package updateSuppression() last saw in front, kept only so publishDiagnostics() has
     *  something to report — nothing decides anything on it. */
    private String lastForegroundPackage = "";
    /** Scratch for publishDiagnostics()' own getLocationOnScreen() — never viewLocation, which
     *  refreshOrigin() rewrites for the drawing code's benefit. */
    private final int[] diagnosticLocation = new int[2];

    /** Hands the settings panel what this view actually concluded this frame — see
     *  OverlayDiagnostics for why any of this is readable from outside at all. Plain field writes,
     *  no allocation, cheap enough to sit in the frame tick. */
    private void publishDiagnostics() {
        String active = activeStyle();
        OverlayDiagnostics.styleSelected = style;
        OverlayDiagnostics.styleActive = active;
        // Measured, not echoed — see the field comments in OverlayDiagnostics. A separate array
        // from viewLocation on purpose: refreshOrigin() deliberately zeroes that one when the view
        // fills the display, which is the very distinction being reported here.
        getLocationOnScreen(diagnosticLocation);
        OverlayDiagnostics.viewWidth = getWidth();
        OverlayDiagnostics.viewHeight = getHeight();
        OverlayDiagnostics.viewLeft = diagnosticLocation[0];
        OverlayDiagnostics.viewTop = diagnosticLocation[1];
        OverlayDiagnostics.requirePlayerScreen = requirePlayerScreen;
        OverlayDiagnostics.onlyOverMusicApp = onlyOverMusicApp;
        // Kept for after the fact — see OverlayDiagnostics.latchVinyl() for why the live values are
        // never the ones anyone gets to read. Last, so it copies everything set above.
        if (EdgeConfig.STYLE_VINYL.equals(active) && !suppressed) OverlayDiagnostics.latchVinyl();
        OverlayDiagnostics.suppressed = suppressed;
        OverlayDiagnostics.foregroundKnown = foregroundKnown;
        OverlayDiagnostics.trackedAppOnScreen = trackedAppOnScreen;
        OverlayDiagnostics.trackedAppConfirmed = trackedAppConfirmed;
        OverlayDiagnostics.foregroundPackage = lastForegroundPackage;
        OverlayDiagnostics.viewVisible = getVisibility() == VISIBLE;
    }

    /** A couple of pixels of slack: this runs every tick, and re-laying out the window over a
     *  sub-pixel jitter in the modelled position would cost far more than it would ever show. */
    private static boolean closeEnough(float a, float b) {
        return Math.abs(a - b) < 2f;
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

    /**
     * "cassette": the lock screen's own dedicated style (see LockScreenVisualizerPreference and
     * activeStyle()) — a straight port of the web player's own .cassette illustration
     * (index.html/style.css), coordinate for coordinate off its 320x200 viewBox.
     *
     * Unlike "vinyl" (see artRect()'s standalone branch), this does not shrink to a centred icon:
     * it fills the whole screen edge to edge, cropped rather than letterboxed, exactly like the
     * web player's own .cassette — the phone's screen reads as the cassette window either way. In
     * landscape the (landscape-drawn) illustration needs no help; in portrait it is rotated 90°
     * about the screen's centre and the cover-fit scale is measured against the swapped box
     * (screen height as width, screen width as height) so the rotated art still runs edge to edge
     * with no letterboxing — the same trick as the web version's own
     * "@media (orientation: portrait) .cassette__art" rule.
     *
     * The label carries the current track's own artwork, muted like ink on paper the same way
     * the web version's .cassette__art-image is (see cassetteArtColorFilter) — without it a
     * lock-screen "cassette" is unrecognisable at a glance as *this* track's, the one thing an
     * AOD-style screen most needs to say. Still deliberately simpler than the web version in
     * other ways: none of the purely decorative gloss sweep or plastic-grain texture — this view
     * redraws itself up to 30 times a second for as long as the lock screen is up, so it keeps
     * only the moving parts worth that cost: the two reels, turning only while something is
     * actually playing (see advanceCassette()), exactly like "vinyl"'s own rotation.
     */
    private void drawCassette(Canvas canvas) {
        if (displayWidth <= 0 || displayHeight <= 0) refreshDisplaySize();
        float screenW = displayWidth > 0 ? displayWidth : getWidth();
        float screenH = displayHeight > 0 ? displayHeight : getHeight();
        if (screenW <= 0 || screenH <= 0) return;
        refreshOrigin();
        float cx = screenW * 0.5f - viewLocation[0];
        float cy = screenH * 0.5f - viewLocation[1];

        boolean rotate = screenH > screenW;
        float boxW = rotate ? screenH : screenW;
        float boxH = rotate ? screenW : screenH;
        float scale = Math.max(boxW / CASSETTE_VIEWBOX_WIDTH, boxH / CASSETTE_VIEWBOX_HEIGHT);

        canvas.save();
        canvas.translate(cx, cy);
        if (rotate) canvas.rotate(90);
        canvas.scale(scale, scale);
        canvas.translate(-CASSETTE_VIEWBOX_WIDTH * 0.5f, -CASSETTE_VIEWBOX_HEIGHT * 0.5f);

        vinylPaint.reset();
        vinylPaint.setAntiAlias(true);
        buildCassetteShaders();

        // Shell + a raised inner edge, same dark navy the web player's own .cover background (and
        // "vinyl"'s own VINYL_VOID_COLOR) sit on, kept the darkest thing on screen on purpose.
        vinylPaint.setStyle(Paint.Style.FILL);
        vinylPaint.setColor(withAlpha(VINYL_VOID_COLOR, 255));
        canvas.drawRoundRect(8, 8, 312, 192, 16, 16, vinylPaint);

        // A soft top-left spotlight and a darker taper into the corners, painted straight onto
        // the flat shell fill above — see .cassette__case-light/.cassette__case-vignette in the
        // web version for the same idea. What keeps a flat fill from reading as a flat fill.
        vinylPaint.setShader(cassetteCaseLightShader);
        canvas.drawRoundRect(8, 8, 312, 192, 16, 16, vinylPaint);
        vinylPaint.setShader(cassetteCaseVignetteShader);
        canvas.drawRoundRect(8, 8, 312, 192, 16, 16, vinylPaint);
        vinylPaint.setShader(null);

        vinylPaint.setStyle(Paint.Style.STROKE);
        vinylPaint.setStrokeWidth(1.5f);
        vinylPaint.setColor(withAlpha(Color.WHITE, 26));
        canvas.drawRoundRect(14, 14, 306, 186, 12, 12, vinylPaint);

        // The label: the current track's own artwork, cropped to fill and muted like ink on
        // paper — see cassetteArtColorFilter — the same treatment the web version's own
        // .cassette__art-image gives it, and the same bitmap "vinyl" already has on hand
        // (setAlbumArt() is called on every track change regardless of which style is active). A
        // plain panel shows through until the first track loads, same as the web version.
        Bitmap cassetteArt = vinylBitmap;
        BitmapShader cassetteArtShader = vinylShader;
        vinylPaint.setStyle(Paint.Style.FILL);
        if (cassetteArt != null && cassetteArtShader != null && !cassetteArt.isRecycled()) {
            float labelW = 228f;
            float labelH = 58f;
            float artScale = Math.max(labelW / cassetteArt.getWidth(), labelH / cassetteArt.getHeight());
            vinylMatrix.setScale(artScale, artScale);
            vinylMatrix.postTranslate(
                22f - (cassetteArt.getWidth() * artScale - labelW) * 0.5f,
                20f - (cassetteArt.getHeight() * artScale - labelH) * 0.5f
            );
            cassetteArtShader.setLocalMatrix(vinylMatrix);
            vinylPaint.setShader(cassetteArtShader);
            vinylPaint.setColorFilter(cassetteArtColorFilter);
            canvas.drawRoundRect(22, 20, 250, 78, 6, 6, vinylPaint);
            vinylPaint.setShader(null);
            vinylPaint.setColorFilter(null);
        } else {
            vinylPaint.setColor(withAlpha(Color.WHITE, 22));
            canvas.drawRoundRect(22, 20, 250, 78, 6, 6, vinylPaint);
        }

        // The brand tab: three flat bands in the album's own three accents, a printed colour
        // spine like a real cassette's rather than one two-colour gradient block — see
        // .cassette__brand-band in the web version for the same idea. Clipped to a rounded rect
        // (not clipRect(), which would square off the tab's own corners) so the bands still read
        // as one rounded tab rather than three stacked rectangles.
        if (cassetteBrandClipPath.isEmpty()) {
            cassetteBrandClipPath.addRoundRect(260, 20, 298, 78, 6, 6, Path.Direction.CW);
        }
        canvas.save();
        canvas.clipPath(cassetteBrandClipPath);
        vinylPaint.setColor(withAlpha(saturate(paletteColorAt(0f)), 255));
        canvas.drawRect(260, 20, 298, 39.33f, vinylPaint);
        vinylPaint.setColor(withAlpha(saturate(paletteColorAt(1f)), 255));
        canvas.drawRect(260, 39.33f, 298, 58.66f, vinylPaint);
        vinylPaint.setColor(withAlpha(saturate(paletteColorAt(2f)), 255));
        canvas.drawRect(260, 58.66f, 298, 78, vinylPaint);
        canvas.restore();
        vinylPaint.setStyle(Paint.Style.STROKE);
        vinylPaint.setStrokeWidth(1f);
        vinylPaint.setColor(withAlpha(Color.BLACK, 89));
        canvas.drawRoundRect(260, 20, 298, 78, 6, 6, vinylPaint);

        // The window the reels sit behind, and the run of tape strung between them.
        vinylPaint.setColor(withAlpha(Color.BLACK, 173));
        canvas.drawRoundRect(30, 90, 290, 176, 12, 12, vinylPaint);
        vinylPaint.setStyle(Paint.Style.STROKE);
        vinylPaint.setStrokeWidth(4f);
        vinylPaint.setStrokeCap(Paint.Cap.ROUND);
        vinylPaint.setColor(withAlpha(Color.WHITE, 77));
        if (cassetteTapePath.isEmpty()) {
            cassetteTapePath.moveTo(108, 150);
            cassetteTapePath.cubicTo(140, 176, 180, 176, 212, 150);
        }
        canvas.drawPath(cassetteTapePath, vinylPaint);
        vinylPaint.setStrokeCap(Paint.Cap.BUTT);

        // The wound tape itself, one coil per reel: the "supply" side (a) starts full and shrinks
        // toward its hub as the track plays, the "take-up" side (b) is the mirror image — see
        // cassetteProgress() and .cassette__coil in the web version for the same idea.
        float progress = cassetteProgress();
        drawCassetteReel(canvas, 108, 134, cassetteReelADeg, 26f - progress * 14f);
        drawCassetteReel(canvas, 212, 134, cassetteReelBDeg, 12f + progress * 14f);

        vinylPaint.setStyle(Paint.Style.FILL);
        vinylPaint.setColor(withAlpha(Color.WHITE, 71));
        vinylPaint.setStrokeWidth(0.7f);
        vinylPaint.setStrokeCap(Paint.Cap.ROUND);
        float[][] screws = {
            { 20, 20, -18 }, { 300, 20, 35 }, { 20, 180, 70 }, { 300, 180, -40 }, { 160, 186, 12 },
        };
        for (float[] screw : screws) {
            canvas.save();
            canvas.rotate(screw[2], screw[0], screw[1]);
            vinylPaint.setStyle(Paint.Style.FILL);
            canvas.drawCircle(screw[0], screw[1], 3.4f, vinylPaint);
            vinylPaint.setStyle(Paint.Style.STROKE);
            vinylPaint.setColor(withAlpha(Color.BLACK, 140));
            canvas.drawLine(screw[0] - 2.6f, screw[1], screw[0] + 2.6f, screw[1], vinylPaint);
            vinylPaint.setStyle(Paint.Style.FILL);
            vinylPaint.setColor(withAlpha(Color.WHITE, 71));
            canvas.restore();
        }

        canvas.restore();
    }

    /** One reel: its cast shadow on the window floor, its own shaded disc, the ring, the wound
     *  tape coil, the palette-coloured hub, its moulded cross, and six short teeth that carry the
     *  rotation — everything else in drawCassette() stays fixed. Translates to (cx, cy) once and
     *  draws everything from there, so cassetteReelDiscShader (built centred on (0,0), see
     *  buildCassetteShaders()) lines up the same way for both reels without needing its own copy
     *  for each. coilRadius is the caller's business (cassetteProgress()) — this method just
     *  paints whatever it's handed. */
    private void drawCassetteReel(Canvas canvas, float cx, float cy, float angleDeg, float coilRadius) {
        // The shadow the reel casts onto the window floor beneath it, offset down-right of the
        // reel's own centre — its own save/restore since that offset differs from the reel's.
        vinylPaint.setStyle(Paint.Style.FILL);
        vinylPaint.setShader(cassetteReelShadowShader);
        canvas.save();
        canvas.translate(cx + 3, cy + 3);
        canvas.drawCircle(0, 0, 31, vinylPaint);
        canvas.restore();

        canvas.save();
        canvas.translate(cx, cy);

        // The reel's own body: a shaded, slightly convex disc rather than a flat window-coloured
        // background with a couple of rings drawn over it — see .cassette__reeldisc. Light —
        // real reels are moulded from translucent white/grey polystyrene, not dark plastic.
        vinylPaint.setShader(cassetteReelDiscShader);
        canvas.drawCircle(0, 0, 29, vinylPaint);
        vinylPaint.setShader(null);

        vinylPaint.setStyle(Paint.Style.STROKE);
        vinylPaint.setStrokeWidth(6f);
        vinylPaint.setColor(withAlpha(Color.WHITE, 102));
        canvas.drawCircle(0, 0, 30, vinylPaint);

        // The wound tape — a solid coil rather than a couple of thin outline rings, sized by
        // whatever cassetteProgress()-derived radius the caller worked out for this reel.
        vinylPaint.setStyle(Paint.Style.FILL);
        vinylPaint.setColor(withAlpha(0xFF1C1712, 255));
        canvas.drawCircle(0, 0, Math.max(0f, coilRadius), vinylPaint);

        vinylPaint.setStyle(Paint.Style.FILL);
        vinylPaint.setColor(withAlpha(saturate(paletteColorAt(0f)), 255));
        canvas.drawCircle(0, 0, 11, vinylPaint);

        // The small moulded cross every reel hub is built around, visible through the
        // accent-coloured centre cap — a recessed detail, dark rather than lit, and rotated a
        // little off-axis the way a real one is never driven in facing exactly true.
        canvas.save();
        canvas.rotate(20);
        vinylPaint.setStyle(Paint.Style.STROKE);
        vinylPaint.setStrokeWidth(1.4f);
        vinylPaint.setStrokeCap(Paint.Cap.ROUND);
        vinylPaint.setColor(withAlpha(Color.BLACK, 102));
        canvas.drawLine(0, -6.5f, 0, 6.5f, vinylPaint);
        canvas.drawLine(-6.5f, 0, 6.5f, 0, vinylPaint);
        canvas.restore();

        canvas.rotate(angleDeg);
        vinylPaint.setStyle(Paint.Style.FILL);
        vinylPaint.setColor(withAlpha(Color.BLACK, 179));
        for (int i = 0; i < 6; i++) {
            canvas.save();
            canvas.rotate(i * 60);
            canvas.drawRoundRect(-1.1f, -16f, 1.1f, -11f, 1f, 1f, vinylPaint);
            canvas.restore();
        }
        canvas.restore();
    }

    /**
     * "cassette"'s own shading shaders, built once on the first frame that draws it and never
     * rebuilt after (guarded by cassetteCaseLightShader alone — all four are always set
     * together). See the field comments for why these don't need buildVinylShaders()'s own
     * "rebuild if the size changed" logic: every coordinate here is already in drawCassette()'s
     * fixed 320x200 viewBox space, which never changes size the way the disc's screen-pixel
     * radius does.
     */
    private void buildCassetteShaders() {
        if (cassetteCaseLightShader != null) return;
        cassetteCaseLightShader = new RadialGradient(
            69, 30, 300,
            new int[] { withAlpha(Color.WHITE, 41), withAlpha(Color.WHITE, 8), withAlpha(Color.WHITE, 0) },
            new float[] { 0f, 0.5f, 1f },
            Shader.TileMode.CLAMP
        );
        cassetteCaseVignetteShader = new RadialGradient(
            160, 100, 280,
            new int[] { withAlpha(Color.BLACK, 0), withAlpha(Color.BLACK, 0), withAlpha(Color.BLACK, 100) },
            new float[] { 0f, 0.7f, 1f },
            Shader.TileMode.CLAMP
        );
        // Off-centre towards the top-left, the same cheat the shell's own case-light above uses —
        // a gradient simply centred off to one side of what it's painted on, rather than an
        // Android Shader's local matrix (which would need resetting per reel to stay off-centre
        // in the right direction relative to each one). Light — real reels are moulded from
        // translucent white/grey polystyrene; a dark disc here read as illustration, not cassette.
        cassetteReelDiscShader = new RadialGradient(
            -6, -8, 34,
            new int[] { 0xFFEEF0F6, 0xFFB7BAC6, 0xFF54545E },
            new float[] { 0f, 0.5f, 1f },
            Shader.TileMode.CLAMP
        );
        cassetteReelShadowShader = new RadialGradient(
            0, 0, 31,
            new int[] {
                withAlpha(Color.BLACK, 0), withAlpha(Color.BLACK, 0),
                withAlpha(Color.BLACK, 127), withAlpha(Color.BLACK, 0),
            },
            new float[] { 0f, 0.62f, 0.86f, 1f },
            Shader.TileMode.CLAMP
        );
        // Two real-device reports in a row said the album art was barely visible on this label —
        // first on a dark cover (an earlier version here darkened it a second time on top of its
        // own already-dark colours), then, after cutting that darkening, on a washed-out/hazy
        // cover (pulling contrast down toward mid-grey to fix the first report flattened this one
        // further still). Any fixed contrast/brightness correction helps one of those two cases
        // at the other's expense — there is no single number that makes both a near-black cover
        // and an already-pale one equally legible. So this no longer tries to correct tone at
        // all: only a light desaturation remains, for a bit of the "ink on paper" restraint the
        // web version's own filter goes for, without ever pushing the actual cover further from
        // how it really looks. Whatever the cover's own brightness is, it now reads as itself.
        ColorMatrix artMatrix = new ColorMatrix();
        artMatrix.setSaturation(0.95f);
        cassetteArtColorFilter = new ColorMatrixColorFilter(artMatrix);
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
