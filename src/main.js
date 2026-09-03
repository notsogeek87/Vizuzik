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
};

const root = document.documentElement;
const MODE_LABELS = {
  cover: "Pochette",
  bars: "Spectre",
  radial: "Corona",
  aurora: "Aurore",
  nebula: "Nébuleuse",
};

let isPlaying = false;
let currentArt = null;
let currentTrackKey = null;

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
  progress.render();
};

// Quantised to 50 steps: finer than the eye can follow on a glow, and it keeps a quiet
// passage from invalidating styles on every single frame.
function writeVar(name, key, value) {
  const rounded = Math.round(value * 50) / 50;
  if (rounded === cssState[key]) return;
  cssState[key] = rounded;
  root.style.setProperty(name, String(rounded));
}

/* ------------------------------------------------------------------ audio capture */

// Whether AudioCaptureService has been asked to capture Deezer's audio. Only real on Android
// 10+ and after the user grants the system's capture consent; otherwise the visualizer simply
// never receives "audioLevels" events and keeps its own simulated animation.
let captureActive = false;
// Why capture isn't live right now, surfaced in the badge instead of a silent guess: the
// native call's own rejection reason ("unsupported"/"denied"), "service arrêté" if
// AudioCaptureService died on its own, or "aucune réponse" if the call resolved but no
// audioLevels event ever showed up (covers a stuck/hung native call too).
let lastCaptureError = null;
let captureWatchdog = null;

function clearCaptureWatchdog() {
  if (captureWatchdog != null) {
    clearTimeout(captureWatchdog);
    captureWatchdog = null;
  }
}

function stopCapture() {
  clearCaptureWatchdog();
  if (captureActive) {
    captureActive = false;
    DeezerMedia.stopVisualizerCapture().catch(() => {});
  }
}

// Requesting real audio capture is a separate, explicit action from picking a visual style
// (triggered by tapping the capture-status badge itself) — switching visualizations never
// pops the system permission dialog as a surprise.
function requestCapture() {
  if (captureActive) return;
  captureActive = true;
  lastCaptureError = null;
  // Armed the moment the call goes out — not after it resolves — so a native call that
  // never settles at all (neither resolve nor reject, e.g. the system consent flow never
  // returning a result) still surfaces a reason instead of leaving the badge blank forever.
  clearCaptureWatchdog();
  captureWatchdog = setTimeout(() => {
    if (visualizer.captureStatus === "simulated") {
      lastCaptureError = "aucune réponse (la fenêtre système n'a jamais rendu la main)";
      captureActive = false;
    }
  }, 8000);
  DeezerMedia.startVisualizerCapture().catch((err) => {
    clearCaptureWatchdog();
    captureActive = false;
    lastCaptureError = (err && err.message) || String(err);
  });
}

const CAPTURE_STATUS_LABELS = {
  live: "● Son réel",
  silent: "● Son réel (silencieux)",
};

// Answers "is this really reacting to Deezer's audio?" on-screen instead of leaving it a
// mystery, and doubles as the button to opt in: tapping it while simulated is what triggers
// the system permission request (see requestCapture()).
function updateCaptureStatusBadge() {
  if (els.player.hidden) {
    els.captureStatus.hidden = true;
    return;
  }
  const status = visualizer.captureStatus;
  els.captureStatus.hidden = false;
  els.captureStatus.dataset.status = status;

  if (status !== "simulated") {
    els.captureStatus.textContent = CAPTURE_STATUS_LABELS[status];
    els.captureStatus.disabled = true;
    return;
  }

  if (captureActive) {
    els.captureStatus.textContent = "● Connexion…";
    els.captureStatus.disabled = true;
  } else {
    els.captureStatus.textContent = lastCaptureError ? `↻ Réessayer (${lastCaptureError})` : "▶ Activer le son réel";
    els.captureStatus.disabled = false;
  }
}
setInterval(updateCaptureStatusBadge, 500);

/* ------------------------------------------------------------------ display modes */

let toastTimer = null;

function applyDisplayMode(announce) {
  document.body.dataset.mode = displayMode;
  visualizer.setStyle(displayMode);
  // The disc changes size and shape over a 0.7s transition; the canvas needs the new centre
  // as it moves, or the corona would sit where the artwork used to be.
  scheduleFocusRefresh();
  if (announce) showToast(MODE_LABELS[displayMode]);
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
els.captureStatus.addEventListener("click", requestCapture);

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
  } else {
    visualizer.stop();
    visualizer.clear();
    stopCapture();
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
    palette.colors.forEach((rgb, i) => {
      root.style.setProperty(`--c${i + 1}`, rgb.join(", "));
    });
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
  }

  isPlaying = !!state.isPlaying;
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
DeezerMedia.addListener("audioLevels", (data) => {
  if (data && data.levels) {
    visualizer.setLevels(data.levels);
    clearCaptureWatchdog();
    lastCaptureError = null;
  }
});
DeezerMedia.addListener("audioCaptureStopped", () => {
  captureActive = false;
  lastCaptureError = lastCaptureError || "service arrêté";
  clearCaptureWatchdog();
});

document.addEventListener("visibilitychange", () => {
  if (document.visibilityState === "visible") {
    refresh();
  } else {
    // Nothing to animate against a hidden screen; rAF would be throttled anyway, but this
    // also drops the offscreen buffers' work entirely.
    visualizer.stop();
  }
});

window.addEventListener("resize", scheduleFocusRefresh);

applyDisplayMode(false);
refresh();
