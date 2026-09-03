import { registerPlugin } from "@capacitor/core";
import { Visualizer, VISUAL_STYLES } from "./visualizer.js";
import { extractPalette } from "./palette.js";
import { PlaybackProgress } from "./progress.js";

const DeezerMedia = registerPlugin("DeezerMedia");

const els = {
  background: document.getElementById("background"),
  backgroundNext: document.getElementById("background-next"),
  fx: document.getElementById("fx"),
  topbar: document.getElementById("topbar"),
  player: document.getElementById("player"),
  stage: document.getElementById("stage"),
  disc: document.getElementById("disc"),
  cover: document.getElementById("cover"),
  captureStatus: document.getElementById("capture-status"),
  captureSheet: document.getElementById("capture-sheet"),
  captureAccept: document.getElementById("capture-accept"),
  captureLater: document.getElementById("capture-later"),
  modeToggle: document.getElementById("mode-toggle"),
  modeToast: document.getElementById("mode-toast"),
  title: document.getElementById("title"),
  artist: document.getElementById("artist"),
  progress: document.getElementById("progress"),
  progressBar: document.getElementById("progress-bar"),
  timeElapsed: document.getElementById("time-elapsed"),
  timeTotal: document.getElementById("time-total"),
  playPause: document.getElementById("play-pause"),
  previous: document.getElementById("previous"),
  next: document.getElementById("next"),
  empty: document.getElementById("empty"),
  permission: document.getElementById("permission"),
  grantAccess: document.getElementById("grant-access"),
  appSelect: document.getElementById("app-select"),
  selectDeezer: document.getElementById("select-deezer"),
  selectSpotify: document.getElementById("select-spotify"),
};

const root = document.documentElement;
const MODE_LABELS = {
  cover: "Pochette",
  bars: "Spectre",
  radial: "Corona",
  aurora: "Aurore",
  nebula: "Nébuleuse",
  cassette: "Cassette",
};

let isPlaying = false;
let currentArt = null;
let currentTrackKey = null;
// Set only right after openMusicApp() sent the user off to pick a track themselves (see the
// startup IIFE near the bottom of this file). Cleared the moment either a track actually starts
// (bringToFront() is attempted then) or the user manually returns first — see the
// nowPlayingChanged and visibilitychange listeners below.
let awaitingFirstTrackAfterLaunch = false;

const DISPLAY_MODE_KEY = "vizuzik:displayMode";
const storedMode = localStorage.getItem(DISPLAY_MODE_KEY);
let displayMode = VISUAL_STYLES.includes(storedMode) ? storedMode : "cover";

const visualizer = new Visualizer(els.fx);
visualizer.setFocusElement(els.disc);

const progress = new PlaybackProgress(
  { root: els.progress, bar: els.progressBar, elapsed: els.timeElapsed, total: els.timeTotal },
  { onSeek: (position) => requestSeek(position) }
);

/** Re-anchors the bar on whatever Deezer currently reports. */
async function syncPosition() {
  const state = await DeezerMedia.getPosition();
  if (!state || !state.active) return null;
  progress.setTrack({
    position: state.position || 0,
    duration: state.duration || 0,
    isPlaying: !!state.isPlaying,
  });
  return state;
}

// How far playback may sit from the requested position before the seek counts as ignored.
// Generous enough to cover the verification delay plus Deezer's own buffering.
const SEEK_TOLERANCE_MS = 4000;
const SEEK_VERIFY_DELAY_MS = 900;

/**
 * Seeking is fire-and-forget on the Android side: MediaSession.seekTo() can be accepted and
 * then quietly ignored by the player. So the result is checked rather than assumed — a bar
 * sitting at a position playback never reached is worse than an honest message.
 */
async function requestSeek(position) {
  try {
    await DeezerMedia.seek({ position });
  } catch (err) {
    showToast("Déplacement refusé", 2200);
    syncPosition().catch(() => {});
    return;
  }
  setTimeout(async () => {
    try {
      const state = await syncPosition();
      if (state && Math.abs((state.position || 0) - position) > SEEK_TOLERANCE_MS) {
        showToast("Deezer ignore le déplacement", 2600);
      }
    } catch (err) {
      // Nothing to report: the periodic re-anchor will straighten the bar out anyway.
    }
  }, SEEK_VERIFY_DELAY_MS);
}

/* ------------------------------------------------------------------ reactive CSS vars */

// The visualizer owns the only animation frame loop in the app; the DOM's beat-reactive
// styling rides along on it through three custom properties. Values are written only when
// they actually move, so a quiet passage costs no style invalidation at all.
const cssState = { beat: -1, level: -1, bass: -1 };

visualizer.onFrame = ({ beat, level, bass }) => {
  writeVar("--beat", "beat", beat);
  writeVar("--level", "level", level);
  writeVar("--bass", "bass", bass);
  syncPaletteVars();
  progress.render();
};

// Without real audio the engine slowly travels the artwork's palette instead of flashing on a
// tempo it doesn't know (see visualizer.js), so the interface re-reads its colours from the
// engine rather than writing them once per track — canvas and DOM are then never two different
// shades of the same album. Throttled and diffed: these three properties are inherited by the
// whole tree, so writing them repaints everything.
const COLOR_SYNC_MS = 120;
const cssColors = ["", "", ""];
let lastColorSyncAt = 0;

function syncPaletteVars() {
  const now = performance.now();
  if (now - lastColorSyncAt < COLOR_SYNC_MS) return;
  lastColorSyncAt = now;
  for (let i = 0; i < cssColors.length; i++) {
    const c = visualizer.displayColor(i);
    const value = `${c[0] | 0}, ${c[1] | 0}, ${c[2] | 0}`;
    if (value === cssColors[i]) continue;
    cssColors[i] = value;
    root.style.setProperty(`--c${i + 1}`, value);
  }
}

// Quantised to 50 steps: finer than the eye can follow on a glow, and it keeps a quiet
// passage from invalidating styles on every single frame.
function writeVar(name, key, value) {
  const rounded = Math.round(value * 50) / 50;
  if (rounded === cssState[key]) return;
  cssState[key] = rounded;
  root.style.setProperty(name, String(rounded));
}

/* ------------------------------------------------------------------ music app selection */

// Vizuzik can follow either Deezer or Spotify. Resolved once at startup: whichever of the two
// is actually installed decides it outright, and a person is only asked when both are — the
// one case Vizuzik genuinely can't guess. The answer is remembered, same pattern as the capture
// consent below, so it's asked at most once per install.
const MUSIC_APP_KEY = "vizuzik:musicApp";

function readMusicApp() {
  try {
    const stored = localStorage.getItem(MUSIC_APP_KEY);
    return stored === "deezer" || stored === "spotify" ? stored : null;
  } catch (err) {
    return null;
  }
}

function rememberMusicApp(app) {
  try {
    localStorage.setItem(MUSIC_APP_KEY, app);
  } catch (err) {
    /* see readMusicApp() */
  }
}

/** Shows the app-select screen and resolves once the user taps one of the two buttons. */
function askMusicApp() {
  return new Promise((resolve) => {
    els.appSelect.hidden = false;
    const pick = (app) => {
      els.selectDeezer.removeEventListener("click", onDeezer);
      els.selectSpotify.removeEventListener("click", onSpotify);
      els.appSelect.hidden = true;
      resolve(app);
    };
    const onDeezer = () => pick("deezer");
    const onSpotify = () => pick("spotify");
    els.selectDeezer.addEventListener("click", onDeezer);
    els.selectSpotify.addEventListener("click", onSpotify);
  });
}

/**
 * Resolves which app to track and launch: the stored choice if it's still installed, the one
 * installed app if there's only one, null if neither is, and the app-select screen only when
 * both are present and nothing was chosen yet.
 */
async function resolveMusicApp() {
  let detected;
  try {
    detected = await DeezerMedia.detectMusicApps();
  } catch (err) {
    // Older native build without detectMusicApps(): behave exactly as before, Deezer-only.
    return "deezer";
  }
  const { deezerInstalled, spotifyInstalled } = detected;
  const stored = readMusicApp();
  if (stored === "deezer" && deezerInstalled) return "deezer";
  if (stored === "spotify" && spotifyInstalled) return "spotify";

  let resolved;
  if (deezerInstalled && !spotifyInstalled) resolved = "deezer";
  else if (spotifyInstalled && !deezerInstalled) resolved = "spotify";
  else if (!deezerInstalled && !spotifyInstalled) resolved = null;
  else resolved = await askMusicApp();

  if (resolved) rememberMusicApp(resolved);
  return resolved;
}

/* ------------------------------------------------------------------ audio capture */

// Real capture needs Android's MediaProjection consent — the system "start recording" dialog.
// It cannot be avoided (CAPTURE_AUDIO_OUTPUT is a privileged permission), so everything here is
// about meeting it as rarely as possible: explain it once before it appears, keep the grant it
// produces alive for as long as the app lives, and never re-ask on a whim.

const CAPTURE_INTENT_KEY = "vizuzik:realAudio";

// "on" once a grant actually went through, "off" once the user declined it, null while they've
// never been asked. An "on" user is taken straight to the system dialog on the next launch
// rather than having to find the badge first and then face the dialog anyway.
let captureIntent = readCaptureIntent();
// Assumed until the native side says otherwise, so an older native build without
// getCaptureState() still gets the manual badge rather than a permanently disabled one.
let captureSupported = true;
// AudioCaptureService alive: the native truth, re-synced on every resume. The flags below live
// in the webview and are wiped whenever it's recreated; the service outlives that.
let captureRunning = false;
let capturePending = false;
// Set once the native side has answered (or proved too old to answer). Nothing is requested
// before that: asking blind would mean a round-trip for a capture that may already be running,
// or on a device that can't capture at all.
let captureStateKnown = false;
// At most one automatic request per app session — a decline is never nagged.
let autoRequested = false;
let lastCaptureError = null;
let captureWatchdog = null;

// The native rejection reasons, said in the language of the person reading the badge.
const CAPTURE_ERROR_LABELS = {
  denied: "autorisation refusée",
  unsupported: "appareil non compatible",
};

function readCaptureIntent() {
  try {
    const stored = localStorage.getItem(CAPTURE_INTENT_KEY);
    return stored === "on" || stored === "off" ? stored : null;
  } catch (err) {
    // Storage disabled: the flow still works, it just asks again next launch.
    return null;
  }
}

function rememberCaptureIntent(value) {
  captureIntent = value;
  try {
    localStorage.setItem(CAPTURE_INTENT_KEY, value);
  } catch (err) {
    /* see readCaptureIntent() */
  }
}

function clearCaptureWatchdog() {
  if (captureWatchdog != null) {
    clearTimeout(captureWatchdog);
    captureWatchdog = null;
  }
}

/**
 * Re-reads the native capture state and, if the user already opted in, gets the consent out of
 * the way immediately. Called on launch and on every resume.
 */
async function syncCaptureState() {
  try {
    const state = await DeezerMedia.getCaptureState();
    captureSupported = !!state.supported;
    captureRunning = !!state.running;
    if (captureRunning) {
      capturePending = false;
      lastCaptureError = null;
      clearCaptureWatchdog();
    }
  } catch (err) {
    // Older native build without getCaptureState(): leave the manual badge as the only path.
  }
  captureStateKnown = true;
  maybeAutoRequestCapture();
}

// The whole point of remembering the opt-in: a returning user gets the system dialog once, at a
// moment they're already looking at the player, instead of tapping a badge to summon it. Guarded
// so it can never fire in the background, on a screen that isn't the player, or a second time.
function maybeAutoRequestCapture() {
  if (!captureStateKnown || captureIntent !== "on" || !captureSupported) return;
  if (autoRequested || captureRunning || capturePending) return;
  if (els.player.hidden || document.visibilityState !== "visible") return;
  autoRequested = true;
  startCapture();
}

function startCapture() {
  if (captureRunning || capturePending || !captureSupported) return;
  capturePending = true;
  lastCaptureError = null;
  // Armed the moment the call goes out — not after it resolves — so a native call that never
  // settles at all (the system consent flow never returning a result) still surfaces a reason
  // instead of leaving the badge stuck on "Connexion…" forever. Long enough that someone simply
  // reading the system dialog never trips it.
  clearCaptureWatchdog();
  captureWatchdog = setTimeout(() => {
    captureWatchdog = null;
    if (!captureRunning) {
      capturePending = false;
      lastCaptureError = "la fenêtre système n'a pas répondu";
    }
  }, 45000);

  DeezerMedia.startVisualizerCapture()
    .then(() => {
      clearCaptureWatchdog();
      capturePending = false;
      captureRunning = true;
      rememberCaptureIntent("on");
    })
    .catch((err) => {
      clearCaptureWatchdog();
      capturePending = false;
      const reason = (err && err.message) || String(err);
      if (reason === "unsupported") {
        captureSupported = false;
      } else if (reason === "denied") {
        // Declining is an answer, not a failure: remember it so no later launch pops the dialog
        // again by itself. The badge is still there if they change their mind.
        rememberCaptureIntent("off");
      }
      lastCaptureError = CAPTURE_ERROR_LABELS[reason] || reason;
    });
}

/* --- the explainer sheet --- */

// Shown once, before the very first system dialog. The dialog itself talks about recording the
// screen, which is alarming and misleading here; arriving at it already knowing what it's for
// and which button to press is the difference between an intrusion and a formality.
let sheetCloseTimer = null;

function openCaptureSheet() {
  clearTimeout(sheetCloseTimer);
  els.captureSheet.hidden = false;
  // One frame between "in the DOM" and "animating in", or the transition never plays.
  requestAnimationFrame(() => els.captureSheet.classList.add("is-open"));
}

function closeCaptureSheet() {
  els.captureSheet.classList.remove("is-open");
  sheetCloseTimer = setTimeout(() => {
    els.captureSheet.hidden = true;
  }, 260);
}

function onCaptureBadgeClick() {
  if (captureRunning || capturePending) return;
  // First time only: explain, then hand over to the system. Afterwards they know what's coming,
  // so an extra screen would just be another tap between them and the music.
  if (captureIntent === null) {
    openCaptureSheet();
    return;
  }
  startCapture();
}

/* --- the badge --- */

// Answers "is this really reacting to Deezer's audio?" on-screen instead of leaving it a
// mystery, and doubles as the way to opt in: tapping it while inactive is what starts the
// consent flow (see onCaptureBadgeClick()).
function updateCaptureStatusBadge() {
  // Nothing to offer on a device that can't capture, and nothing to report off the player.
  if (els.player.hidden || !captureSupported) {
    els.captureStatus.hidden = true;
    return;
  }
  els.captureStatus.hidden = false;

  if (capturePending) {
    setBadge("simulated", "● Connexion…", true);
    return;
  }

  if (captureRunning) {
    const status = visualizer.captureStatus;
    if (status === "silent") {
      setBadge("silent", "● Son réel (silencieux)", true);
    } else if (status === "live") {
      setBadge("live", "● Son réel", true);
    } else {
      // Capture is alive but no levels are coming through: normal while paused, and worth
      // saying plainly rather than inviting a pointless second trip through the dialog.
      setBadge("live", isPlaying ? "● Son réel (signal faible)" : "● Son réel prêt", true);
    }
    return;
  }

  setBadge("simulated", lastCaptureError ? `↻ Son réel (${lastCaptureError})` : "▶ Activer le son réel", false);
}

function setBadge(status, label, disabled) {
  els.captureStatus.dataset.status = status;
  els.captureStatus.textContent = label;
  els.captureStatus.disabled = disabled;
}

setInterval(updateCaptureStatusBadge, 500);

/* ------------------------------------------------------------------ display modes */

let toastTimer = null;

function applyDisplayMode(announce) {
  document.body.dataset.mode = displayMode;
  visualizer.setStyle(displayMode);
  // setStyle() wipes the scene; the impulse goes after it so the new one arrives lit.
  if (announce) visualizer.pulse(0.6);
  // The disc changes size and shape over a 0.7s transition; the canvas needs the new centre
  // as it moves, or the corona would sit where the artwork used to be.
  scheduleFocusRefresh();
  if (announce) showToast(MODE_LABELS[displayMode]);
  syncOrientationLock();
}

// Cassette mode is drawn cassette-side up (landscape); rather than only relying on the CSS
// rotation trick for whichever way the phone already happens to be held, this actually turns
// the screen for the user — the same way a video player forces landscape for fullscreen.
// Guarded against re-firing on every applyDisplayMode(false) call (nowPlayingChanged fires
// that often) so the native side isn't asked to re-apply the same orientation repeatedly.
let orientationLockedFor = null;
function syncOrientationLock() {
  if (orientationLockedFor === displayMode) return;
  orientationLockedFor = displayMode;
  const request = displayMode === "cassette" ? DeezerMedia.lockLandscape() : DeezerMedia.unlockOrientation();
  // No native implementation on the web (dev preview): fails silently there.
  request.catch(() => {});
}

function showToast(label, durationMs = 1400) {
  els.modeToast.textContent = label;
  els.modeToast.classList.add("is-visible");
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => els.modeToast.classList.remove("is-visible"), durationMs);
}

function cycleDisplayMode() {
  const nextIndex = (VISUAL_STYLES.indexOf(displayMode) + 1) % VISUAL_STYLES.length;
  displayMode = VISUAL_STYLES[nextIndex];
  localStorage.setItem(DISPLAY_MODE_KEY, displayMode);
  applyDisplayMode(true);
}

function scheduleFocusRefresh() {
  for (const delay of [0, 120, 320, 560, 760]) {
    setTimeout(() => visualizer.updateFocus(), delay);
  }
}

els.modeToggle.addEventListener("click", cycleDisplayMode);
els.captureStatus.addEventListener("click", onCaptureBadgeClick);
els.captureAccept.addEventListener("click", () => {
  closeCaptureSheet();
  startCapture();
});
els.captureLater.addEventListener("click", closeCaptureSheet);
// Tapping the backdrop is the same as "plus tard" — the intent stays unanswered, so the sheet
// comes back the next time they ask for it rather than the choice being made for them.
els.captureSheet.addEventListener("click", (event) => {
  if (event.target === els.captureSheet) closeCaptureSheet();
});

/* ------------------------------------------------------------------ swipe & tap */

// Past this much horizontal travel, releasing changes track.
const SWIPE_TRIGGER_PX = 64;
// Below this, the gesture is still a tap rather than a drag.
const TAP_SLOP_PX = 12;
const TAP_MAX_MS = 450;
const FLING_MS = 260;

let gesture = null;

// --swipe-x is written unitless (see .disc in style.css): the stylesheet turns it into both a
// translation and a rotation, and calc() can only derive an angle from a plain number.
function setSwipeOffset(px, opacity) {
  root.style.setProperty("--swipe-x", String(Math.round(px)));
  root.style.setProperty("--swipe-o", String(opacity));
}

function resetSwipe() {
  setSwipeOffset(0, 1);
}

/**
 * Carousel release: the artwork flies out the way the finger went, is teleported to the far
 * side with no transition, then glides back to centre — so a track change reads as one
 * continuous movement rather than a disc that vanishes and pops back.
 */
function flingDisc(direction) {
  const distance = window.innerWidth * 0.6;
  setSwipeOffset(direction * distance, 0);
  setTimeout(() => {
    document.body.classList.remove("is-swipe-releasing");
    setSwipeOffset(-direction * distance, 0);
    // Two frames: the jump has to be painted before the transition is re-armed, otherwise the
    // browser coalesces both values and animates straight across the screen.
    requestAnimationFrame(() =>
      requestAnimationFrame(() => {
        document.body.classList.add("is-swipe-releasing");
        resetSwipe();
        setTimeout(() => document.body.classList.remove("is-swipe-releasing"), 320);
      })
    );
  }, FLING_MS);
}

// The controls, the scrub bar and the top bar own their own pointers; a swipe must not start
// on top of them or dragging off a button would skip a track.
function ownsItsPointer(target) {
  return !!(target.closest && target.closest(".controls, .progress, .topbar"));
}

els.player.addEventListener("pointerdown", (event) => {
  if (gesture || ownsItsPointer(event.target)) return;
  gesture = {
    id: event.pointerId,
    x0: event.clientX,
    y0: event.clientY,
    t0: performance.now(),
    dx: 0,
    dy: 0,
    dragging: false,
    onStage: !!(event.target.closest && event.target.closest(".stage")),
  };
  els.player.setPointerCapture(event.pointerId);
  document.body.classList.remove("is-swipe-releasing");
});

els.player.addEventListener("pointermove", (event) => {
  if (!gesture || gesture.id !== event.pointerId) return;
  gesture.dx = event.clientX - gesture.x0;
  gesture.dy = event.clientY - gesture.y0;
  if (!gesture.dragging && Math.abs(gesture.dx) > TAP_SLOP_PX && Math.abs(gesture.dx) > Math.abs(gesture.dy)) {
    gesture.dragging = true;
    document.body.classList.add("is-swiping");
  }
  if (gesture.dragging) {
    // Damped: the artwork trails the finger, which makes the threshold feel like resistance
    // rather than a cliff.
    const offset = gesture.dx * 0.75;
    setSwipeOffset(offset, Math.max(0.35, 1 - Math.abs(offset) / 520));
  }
});

function endGesture(event, cancelled) {
  if (!gesture || gesture.id !== event.pointerId) return;
  const g = gesture;
  gesture = null;
  if (els.player.hasPointerCapture(event.pointerId)) els.player.releasePointerCapture(event.pointerId);
  document.body.classList.remove("is-swiping");
  document.body.classList.add("is-swipe-releasing");

  const horizontal = Math.abs(g.dx) > Math.abs(g.dy);
  if (!cancelled && g.dragging && horizontal && Math.abs(g.dx) > SWIPE_TRIGGER_PX) {
    const goNext = g.dx < 0;
    flingDisc(goNext ? -1 : 1);
    // The gesture itself is a real event: the screen answers the finger now, without waiting
    // for Deezer to confirm the track change.
    visualizer.pulse(0.45);
    Promise.resolve(goNext ? DeezerMedia.next() : DeezerMedia.previous()).catch(() => {});
    return;
  }

  resetSwipe();
  // A tap on the artwork still cycles visualizations — the obvious gesture on a screen you
  // look at from across the room, and the toast names what you landed on.
  if (
    !cancelled &&
    !g.dragging &&
    g.onStage &&
    performance.now() - g.t0 < TAP_MAX_MS &&
    Math.abs(g.dy) < TAP_SLOP_PX
  ) {
    cycleDisplayMode();
  }
}

els.player.addEventListener("pointerup", (event) => endGesture(event, false));
els.player.addEventListener("pointercancel", (event) => endGesture(event, true));

/* ------------------------------------------------------------------ now playing */

function showScreen(screen) {
  els.player.hidden = screen !== "player";
  els.topbar.hidden = screen !== "player";
  els.empty.hidden = screen !== "empty";
  els.permission.hidden = screen !== "permission";

  if (screen === "player") {
    visualizer.start();
    scheduleFocusRefresh();
    // A track that starts after a gap is the natural moment to have the consent behind us.
    maybeAutoRequestCapture();
  } else {
    visualizer.stop();
    visualizer.clear();
    // Deliberately NOT stopping the capture here. This screen is reached whenever Deezer's
    // session merely goes quiet between tracks or on a pause, and tearing the projection down
    // there meant the system consent dialog had to be faced all over again the moment music
    // came back. The service stops on its own when Vizuzik leaves recents.
    // Leave the ambient layer at rest rather than frozen mid-pulse.
    writeVar("--beat", "beat", 0);
    writeVar("--level", "level", 0);
    writeVar("--bass", "bass", 0);
  }
}

/**
 * Cross-fades the blurred backdrop between two stacked layers, so a track change dissolves
 * instead of blinking, and recolours the whole UI from the new artwork.
 */
let bgFront = els.background;
let bgBack = els.backgroundNext;

function setArtwork(art) {
  if (art === currentArt) return;
  currentArt = art;

  bgBack.style.backgroundImage = art ? `url("${art}")` : "";
  bgBack.style.opacity = "1";
  bgFront.style.opacity = "0";
  const swap = bgFront;
  bgFront = bgBack;
  bgBack = swap;

  els.cover.style.backgroundImage = art ? `url("${art}")` : "";

  extractPalette(art).then((palette) => {
    visualizer.setPalette(palette);
    // While the engine loops, syncPaletteVars() melts the interface into the new palette along
    // with the canvas. Off the player screen nothing is looping, so seed the vars directly.
    if (!visualizer.running) {
      palette.colors.forEach((rgb, i) => {
        root.style.setProperty(`--c${i + 1}`, rgb.join(", "));
      });
    }
  });
}

/** Restarts the entrance animations; the reflow is what makes a re-added class replay. */
function playTrackChangeAnimation() {
  document.body.classList.remove("is-changing");
  void document.body.offsetWidth;
  document.body.classList.add("is-changing");
  setTimeout(() => document.body.classList.remove("is-changing"), 900);
}

/**
 * Scrolls a title that doesn't fit rather than truncating it — on a display you glance at
 * from across the room, seeing the whole name matters more than a tidy ellipsis.
 */
function setScrollingText(span, text) {
  span.classList.remove("is-scrolling");
  span.textContent = text;
  requestAnimationFrame(() => {
    const container = span.parentElement;
    if (!container) return;
    const overflow = span.scrollWidth - container.clientWidth;
    if (overflow <= 4) return;
    const distance = overflow + 16;
    span.style.setProperty("--marquee-distance", `${distance}px`);
    span.style.setProperty("--marquee-duration", `${Math.max(9, distance / 26 + 7)}s`);
    span.classList.add("is-scrolling");
  });
}

function setNowPlaying(state) {
  if (!state || !state.active) {
    showScreen("empty");
    currentTrackKey = null;
    return;
  }

  showScreen("player");

  const title = state.title || "Titre inconnu";
  const artist = state.artist || "";
  const trackKey = `${title}::${artist}`;
  if (trackKey !== currentTrackKey) {
    currentTrackKey = trackKey;
    setScrollingText(els.title, title);
    setScrollingText(els.artist, artist);
    playTrackChangeAnimation();
    // A new song has to visibly land. This and the handful of pulses below are the only
    // impulses the screen gets when the audio isn't being captured — all of them tied to
    // something that actually happened, never to a guessed tempo.
    visualizer.pulse(1);
  }

  const wasPlaying = isPlaying;
  isPlaying = !!state.isPlaying;
  if (isPlaying !== wasPlaying) visualizer.pulse(0.55);
  els.playPause.dataset.state = isPlaying ? "playing" : "paused";
  document.body.dataset.state = isPlaying ? "playing" : "paused";
  visualizer.setPlaying(isPlaying);

  progress.setTrack({
    position: state.position || 0,
    duration: state.duration || 0,
    isPlaying,
  });

  setArtwork(state.albumArt || "");
  applyDisplayMode(false);
}

// The media session only reports a position when something changes, so the bar runs on a local
// clock between updates. This re-anchors it against the real one often enough that drift never
// becomes visible, using the position-only call so the album art isn't re-encoded every time.
const POSITION_RESYNC_MS = 5000;
setInterval(() => {
  if (els.player.hidden || document.visibilityState !== "visible") return;
  // Older native build without getPosition(): the local clock alone still drives the bar.
  syncPosition().catch(() => {});
}, POSITION_RESYNC_MS);

async function refresh() {
  const { granted } = await DeezerMedia.checkPermission();
  if (!granted) {
    showScreen("permission");
    return;
  }
  const state = await DeezerMedia.getNowPlaying();
  setNowPlaying(state);
}

/* ------------------------------------------------------------------ wiring */

els.grantAccess.addEventListener("click", () => {
  DeezerMedia.requestPermission();
});

els.playPause.addEventListener("click", () => {
  if (isPlaying) {
    DeezerMedia.pause();
  } else {
    DeezerMedia.play();
  }
});
els.previous.addEventListener("click", () => DeezerMedia.previous());
els.next.addEventListener("click", () => DeezerMedia.next());

DeezerMedia.addListener("nowPlayingChanged", setNowPlaying);
DeezerMedia.addListener("nowPlayingChanged", (state) => {
  // Best-effort return trip: the app was opened because there was nothing to resume, and now
  // something is actually playing — the moment a person picked a track, not just switched to
  // the app to look around. Not guaranteed (see DeezerMediaPlugin.bringToFront()), but this is
  // the one moment it's worth trying.
  if (!awaitingFirstTrackAfterLaunch || !state || !state.active || !state.isPlaying) return;
  awaitingFirstTrackAfterLaunch = false;
  DeezerMedia.bringToFront().catch(() => {});
});
DeezerMedia.addListener("audioLevels", (data) => {
  if (data && data.levels) {
    visualizer.setLevels(data.levels);
    // Levels arriving are proof the capture is live, even if this webview was recreated after
    // the grant and never saw the call that produced it.
    captureRunning = true;
    capturePending = false;
    clearCaptureWatchdog();
    lastCaptureError = null;
  }
});
DeezerMedia.addListener("audioCaptureStopped", () => {
  captureRunning = false;
  capturePending = false;
  lastCaptureError = lastCaptureError || "capture interrompue";
  clearCaptureWatchdog();
});

document.addEventListener("visibilitychange", () => {
  if (document.visibilityState === "visible") {
    // Back in Vizuzik on our own steam (manually, or via bringToFront() already firing this
    // same event): either way there's nothing left to bring back.
    awaitingFirstTrackAfterLaunch = false;
    refresh();
    syncCaptureState();
  } else {
    // Nothing to animate against a hidden screen; rAF would be throttled anyway, but this
    // also drops the offscreen buffers' work entirely.
    visualizer.stop();
  }
});

window.addEventListener("resize", scheduleFocusRefresh);

applyDisplayMode(false);
(async () => {
  // Which app to follow has to be settled first: the native now-playing listener needs it
  // before refresh() can report anything meaningful, and it decides which app (if any) gets
  // auto-launched below.
  const app = await resolveMusicApp();
  if (app) {
    // Awaited: refresh() below reads the now-playing session natively, and that lookup needs
    // to already know which app to look for.
    await DeezerMedia.setMusicAppTarget({ app }).catch(() => {});
  }
  await refresh().catch(() => {});
  syncCaptureState();
  // Cold start only: never repeated on a later resume, since by then the app (or a resumed
  // session) is already exactly where it should be, and redoing any of this mid-session would
  // just steal focus or restart a track the user is deliberately listening to or pausing.
  if (app && !els.player.hidden && !isPlaying) {
    // A session for the tracked app is already there (it kept running in the background, still
    // holding the last track) — just paused. Resuming it straight from here means Vizuzik never
    // has to leave the screen at all, let alone find its way back to it.
    DeezerMedia.play().catch(() => {});
  } else if (app && els.player.hidden) {
    // No session at all: there is no last track to resume, so nothing short of opening the app
    // lets the user pick one. Once they start something, the notification listener picks it up
    // and maybeAutoRequestCapture() takes the system consent dialog off their hands too, same
    // as any other launch — and the nowPlayingChanged listener above tries to bring Vizuzik back.
    awaitingFirstTrackAfterLaunch = true;
    DeezerMedia.openMusicApp({ app }).catch(() => {});
  }
})();
