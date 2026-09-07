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
  cassetteArt: document.getElementById("cassette-art"),
  captureStatus: document.getElementById("capture-status"),
  overlayStatus: document.getElementById("overlay-status"),
  overlaySheet: document.getElementById("overlay-sheet"),
  overlayAccept: document.getElementById("overlay-accept"),
  overlayLater: document.getElementById("overlay-later"),
  edgeSettingsOpen: document.getElementById("edge-settings-open"),
  edgeSettingsSheet: document.getElementById("edge-settings-sheet"),
  edgeSettingsClose: document.getElementById("edge-settings-close"),
  edgeOverlayEnabled: document.getElementById("edge-overlay-enabled"),
  edgeStyle: document.getElementById("edge-style"),
  edgeBand: document.getElementById("edge-band"),
  edgeCocoonFallback: document.getElementById("edge-cocoon-fallback"),
  edgeColorMode: document.getElementById("edge-color-mode"),
  edgeCustomColors: document.getElementById("edge-custom-colors"),
  edgeColor1: document.getElementById("edge-color-1"),
  edgeColor2: document.getElementById("edge-color-2"),
  edgeColor3: document.getElementById("edge-color-3"),
  edgeBarSize: document.getElementById("edge-bar-size"),
  edgeIntensity: document.getElementById("edge-intensity"),
  edgeThickness: document.getElementById("edge-thickness"),
  edgeBrightness: document.getElementById("edge-brightness"),
  edgeSensitivity: document.getElementById("edge-sensitivity"),
  edgeTop: document.getElementById("edge-top"),
  edgeBottom: document.getElementById("edge-bottom"),
  edgeLeft: document.getElementById("edge-left"),
  edgeRight: document.getElementById("edge-right"),
  edgeOnlyMusicApp: document.getElementById("edge-only-music-app"),
  edgeOnlyMusicAppHint: document.getElementById("edge-only-music-app-hint"),
  edgeUsageGrant: document.getElementById("edge-usage-grant"),
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
  recheckAccess: document.getElementById("recheck-access"),
  permissionHint: document.getElementById("permission-hint"),
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
  cocoon: "Cocon",
  cassette: "Cassette",
};

let isPlaying = false;
let currentArt = null;
let currentTrackKey = null;
// Android TV, confirmed by the native side (UiModeManager — see getPlatformInfo() in
// DeezerMediaPlugin.java). Drives the 10-foot CSS (body[data-platform="tv"]) and the default-
// focus calls below; the D-pad/media-key handling further down stays on regardless, since it's
// inert without an actual keydown to react to and so never changes anything for a touch-only
// phone. Resolved once at startup, before the first screen is shown — see detectTvPlatform().
let isTv = false;

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

/* ------------------------------------------------------------------ platform detection */

async function detectTvPlatform() {
  try {
    const info = await DeezerMedia.getPlatformInfo();
    isTv = !!(info && info.isTv);
  } catch (err) {
    // Older native build without getPlatformInfo(), or the web dev preview: behave exactly as
    // on a phone rather than guessing from screen size or input capability.
    isTv = false;
  }
  if (isTv) document.body.dataset.platform = "tv";
  // Locks the fixed landscape orientation right away rather than waiting for a track to start
  // (applyDisplayMode(), the only other caller of syncOrientationLock(), doesn't run again until
  // one does) — otherwise a TV stuck on the empty/permission screen never gets past whatever
  // orientation the very first, pre-detection applyDisplayMode(false) call left it in.
  syncOrientationLock();
}

/* ------------------------------------------------------------------ remote-control navigation */

// A TV remote (and anything else keyboard-driven) has no pointer, so every gesture-only action
// elsewhere in this file — swipe the disc to skip a track, tap the stage to cycle modes — needs
// a key-based way in. Buttons are already focusable; what Chromium's WebView doesn't do on its
// own is move that focus when an arrow key is pressed, so that part happens by hand here. None
// of this reacts unless something actually sends these key events, so it changes nothing for a
// touch-only phone with no keyboard attached.
function focusableControls() {
  return Array.from(document.querySelectorAll("button")).filter((el) => {
    if (el.disabled || el.closest("[hidden]")) return false;
    return el.getClientRects().length > 0;
  });
}

function moveFocus(step) {
  const list = focusableControls();
  if (!list.length) return;
  const index = list.indexOf(document.activeElement);
  const next = index === -1 ? 0 : (index + step + list.length) % list.length;
  list[next].focus();
}

const FOCUS_STEP = { ArrowRight: 1, ArrowDown: 1, ArrowLeft: -1, ArrowUp: -1 };

document.addEventListener("keydown", (event) => {
  switch (event.key) {
    case "MediaPlayPause":
      event.preventDefault();
      els.playPause.click();
      return;
    case "MediaTrackNext":
      event.preventDefault();
      DeezerMedia.next().catch(() => {});
      return;
    case "MediaTrackPrevious":
      event.preventDefault();
      DeezerMedia.previous().catch(() => {});
      return;
  }
  if (event.key in FOCUS_STEP) {
    event.preventDefault();
    moveFocus(FOCUS_STEP[event.key]);
  }
});

/** A remote needs something focused to start navigating from — a mouse/touch user never does. */
function focusForRemote(el) {
  if (isTv && el) el.focus();
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
    focusForRemote(els.selectDeezer);
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

/* ------------------------------------------------------------------ audio source */

// There is exactly one, and nothing to choose: a Visualizer attached to the music app's own audio
// session, natively (see TrackedSessionAudioSource.java), feeding both this full-screen player and
// the edge overlay through the same bridge.
//
// Three selectable sources used to live here — the phone's microphone, the tracked app's output via
// MediaProjection, and "off" for a purely invented ambient animation. The microphone heard the room
// rather than the music; MediaProjection made Android show its "start recording your screen" dialog
// on every single launch, since it never remembers a past grant; and "off" answered "is this
// reacting to the music?" with something convincing that wasn't reacting to anything. Attaching to
// the player's own session needs none of that — only RECORD_AUDIO, granted once and remembered.
//
// Android still names that permission "microphone" in its dialog, which is the one confusing part
// of this, and unavoidable: it is what Visualizer requires.
// null until an answer is known: "not asked yet" and "refused" are different things to say on the
// badge, and a failed native call must not be reported as a refusal.
let audioPermissionGranted = null;

/** Asked once, at launch — this is the only call that can show the system dialog. */
async function requestAudioPermission() {
  await resolveAudioPermission(() => DeezerMedia.requestAudioPermission());
}

/** Re-read on every resume, without ever prompting: the grant can be made or withdrawn from
 *  Android's Settings while Vizuzik is in the background. Granting it there is also what the
 *  native side needs to hear about, so it can attach to the session already playing rather than
 *  waiting for the next track. */
async function syncAudioPermission() {
  await resolveAudioPermission(() => DeezerMedia.getAudioPermission());
}

async function resolveAudioPermission(ask) {
  try {
    const result = await ask();
    audioPermissionGranted = !!(result && result.granted);
  } catch (err) {
    // Older native build without the method, or the call failed. Left unknown rather than
    // refused — levels may well still arrive, and the badge reads them directly.
    audioPermissionGranted = null;
  }
  updateCaptureStatusBadge();
}

/* --- the badge --- */

// Purely informative now that there is nothing to switch: it answers "is this really reacting to
// the music right now?" on screen, instead of leaving it a guess.
function updateCaptureStatusBadge() {
  if (els.player.hidden) {
    els.captureStatus.hidden = true;
    return;
  }
  els.captureStatus.hidden = false;

  // Real levels win over everything below: whatever was concluded about the permission, audio
  // that is visibly arriving is the more truthful answer.
  const status = visualizer.captureStatus;
  if (status === "live") {
    setBadge("live", "● Son de la musique");
    return;
  }
  if (status === "silent") {
    setBadge("silent", "● Silencieux");
    return;
  }
  if (audioPermissionGranted === false) {
    setBadge("simulated", "○ Ambiance (autorisation refusée)");
    return;
  }
  setBadge("simulated", isPlaying ? "● En attente du son" : "○ Ambiance");
}

function setBadge(status, label) {
  els.captureStatus.dataset.status = status;
  els.captureStatus.textContent = label;
}

setInterval(updateCaptureStatusBadge, 500);

/* --- edge overlay: a glow drawn over the music app itself (Deezer/Spotify/YouTube Music/a
   local player), MuViz Edge-style, visible even while Vizuzik itself is backgrounded --- */

// Whether the user has turned this on. Separate from whether it's actually running right now —
// syncEdgeOverlay() below decides that from several conditions at once.
const EDGE_OVERLAY_ENABLED_KEY = "vizuzik:edgeOverlay";
// Whether the one-time explainer sheet has already been shown: the system "display over other
// apps" screen is opened only after someone already knows what it's for and that it's optional.
const EDGE_OVERLAY_SHEET_SEEN_KEY = "vizuzik:edgeOverlaySheetSeen";
// Purely cosmetic: what the color pickers should show next time the settings panel opens.
// EdgeConfig (native) is the actual source of truth for what the overlay renders — this is
// just so the sheet doesn't reset to the default swatches after a cold start.
const EDGE_CUSTOM_COLORS_KEY = "vizuzik:edgeCustomColors";
const EDGE_DEFAULT_COLORS = "#7c5cff,#ec4899,#38bdf8";

function isEdgeOverlayEnabled() {
  try {
    return localStorage.getItem(EDGE_OVERLAY_ENABLED_KEY) === "on";
  } catch (err) {
    return false;
  }
}

function rememberEdgeOverlayEnabled(enabled) {
  try {
    localStorage.setItem(EDGE_OVERLAY_ENABLED_KEY, enabled ? "on" : "off");
  } catch (err) {
    /* see isEdgeOverlayEnabled() */
  }
}

function hasOverlaySheetBeenSeen() {
  try {
    return localStorage.getItem(EDGE_OVERLAY_SHEET_SEEN_KEY) === "on";
  } catch (err) {
    return false;
  }
}

function rememberOverlaySheetSeen() {
  try {
    localStorage.setItem(EDGE_OVERLAY_SHEET_SEEN_KEY, "on");
  } catch (err) {
    /* see hasOverlaySheetBeenSeen() */
  }
}

// Assumed until the native side says otherwise.
let overlaySupported = true;
let overlayPermissionGranted = false;
let edgeOverlayEnabled = isEdgeOverlayEnabled();
// The native truth: whether OverlayEdgeGlowService is actually running right now. Only
// syncEdgeOverlay() ever changes this, and always right after telling the native side to match.
let edgeOverlayRunning = false;

// "Usage access": the extra grant the "only over the music app" setting needs, since nothing
// else can tell an overlay window which app is actually on screen (see ForegroundApp.java).
// Optional by design — without it the overlay simply keeps showing everywhere, so the setting
// degrades to "off" rather than to an overlay that never appears.
let usageAccessGranted = false;

async function syncUsageAccess() {
  try {
    const state = await DeezerMedia.checkUsageAccess();
    usageAccessGranted = !!(state && state.granted);
  } catch (err) {
    // Older native build without the method: leave it unknown and say nothing about it.
    usageAccessGranted = false;
  }
  updateUsageAccessHint();
}

function updateUsageAccessHint() {
  if (!els.edgeOnlyMusicAppHint) return;
  // The grant is what lets Vizuzik know which app is on screen at all, so it gates this switch
  // *and* the cocoon's fallback. Offered as its own button rather than only on flipping the
  // switch: the switch has a default, so someone who agrees with it never touches it and would
  // never be asked — which is exactly how both features ended up silently doing nothing.
  els.edgeUsageGrant.hidden = usageAccessGranted;
  els.edgeOnlyMusicAppHint.textContent = usageAccessGranted
    ? "Masque tout dès que Deezer n'est plus à l'écran, au lieu de basculer sur le style de repli."
    : "Sans l'accès aux données d'utilisation, Vizuzik ne sait pas quelle app est à l'écran : ni ce réglage ni le repli du Cocon ne peuvent fonctionner.";
}

/** Re-reads the native "display over other apps" grant. Called on launch and on every resume. */
async function syncOverlayPermission() {
  try {
    const state = await DeezerMedia.checkOverlayPermission();
    overlaySupported = !!state.supported;
    overlayPermissionGranted = !!state.granted;
  } catch (err) {
    overlaySupported = false;
  }
  syncEdgeOverlay();
}

/**
 * The single place that decides whether OverlayEdgeGlowService should be running, and the only
 * function allowed to start or stop it — called after every event that could change the answer
 * (a play/pause, a track disappearing, showing/hiding the player screen, granting the
 * permission, toggling the setting, or Vizuzik itself leaving or regaining the foreground).
 *
 * The last condition is the point of the whole feature: the overlay exists precisely for the
 * moments Vizuzik *isn't* what's on screen, since its own full-screen player already shows
 * everything the overlay would.
 */
function syncEdgeOverlay() {
  const shouldRun =
    edgeOverlayEnabled &&
    overlaySupported &&
    overlayPermissionGranted &&
    isPlaying &&
    !els.player.hidden &&
    document.visibilityState !== "visible";
  if (shouldRun === edgeOverlayRunning) return;
  edgeOverlayRunning = shouldRun;
  if (shouldRun) {
    DeezerMedia.startEdgeOverlay().catch(() => {});
  } else {
    DeezerMedia.stopEdgeOverlay().catch(() => {});
  }
  updateOverlayStatusBadge();
}

/**
 * Turns the overlay on or off — the one piece of state that outlives this screen, since
 * EdgeOverlayController starts the overlay natively from it even when Vizuzik's webview never
 * runs. Two controls share this: tapping the Edge badge in the topbar, and the "Mode
 * superposition" switch in the settings panel.
 */
function setEdgeOverlayEnabled(enabled) {
  edgeOverlayEnabled = enabled;
  rememberEdgeOverlayEnabled(enabled);
  // Mirrored natively (EdgeOverlayPreference) so EdgeOverlayController — which has no access to
  // this page's localStorage — can start Edge Visualizer itself the first time a track plays,
  // even if Vizuzik's own webview never runs again this session.
  DeezerMedia.setEdgeOverlayEnabled({ enabled }).catch(() => {});
  if (enabled && !overlayPermissionGranted) {
    // Close the settings panel first: both sheets sit at the same z-index and this one comes
    // later in the DOM, so the explainer would otherwise open *behind* it — its "Continuer"
    // button unreachable, and the switch left on for an overlay that can never run.
    closeEdgeSettingsSheet();
    if (!hasOverlaySheetBeenSeen()) {
      openOverlaySheet();
    } else {
      DeezerMedia.requestOverlayPermission().catch(() => {});
    }
  }
  // Whichever of the two controls was used, the other has to follow.
  els.edgeOverlayEnabled.checked = enabled;
  updateOverlayStatusBadge();
  syncEdgeOverlay();
}

/** Tapping the badge: off/never-granted → on (asking for the permission first if needed); on → off. */
function toggleEdgeOverlay() {
  if (!overlaySupported) return;
  setEdgeOverlayEnabled(!edgeOverlayEnabled);
}

/* --- the explainer sheet (edge overlay only) --- */

let overlaySheetCloseTimer = null;

function openOverlaySheet() {
  clearTimeout(overlaySheetCloseTimer);
  els.overlaySheet.hidden = false;
  requestAnimationFrame(() => {
    els.overlaySheet.classList.add("is-open");
    focusForRemote(els.overlayAccept);
  });
}

function closeOverlaySheet() {
  els.overlaySheet.classList.remove("is-open");
  overlaySheetCloseTimer = setTimeout(() => {
    els.overlaySheet.hidden = true;
  }, 260);
}

/* --- the badge --- */

function setOverlayBadge(status, label) {
  els.overlayStatus.dataset.status = status;
  els.overlayStatus.textContent = label;
}

function updateOverlayStatusBadge() {
  if (els.player.hidden || !overlaySupported) {
    els.overlayStatus.hidden = true;
    els.edgeSettingsOpen.hidden = true;
    return;
  }
  els.overlayStatus.hidden = false;
  els.edgeSettingsOpen.hidden = false;

  // Whether it's the system permission or just the setting that's missing, the action is the
  // same tap either way — no reason to tell the two apart in the label itself.
  if (!overlayPermissionGranted || !edgeOverlayEnabled) {
    setOverlayBadge("simulated", "▶ Activer Edge Visualizer");
    return;
  }
  setOverlayBadge(edgeOverlayRunning ? "live" : "silent", edgeOverlayRunning ? "● Edge actif" : "● Edge prêt");
}

setInterval(updateOverlayStatusBadge, 500);

/* --- edge visualizer settings panel: every control here mirrors into EdgeConfig (native
   SharedPreferences) via DeezerMedia.setEdgeConfig(), so OverlayEdgeGlowService — which has no
   access to this page's localStorage — picks up an edit live, without a restart. --- */

const EDGE_SETTINGS_DEFAULTS = {
  style: "bars",
  band: "full",
  cocoonFallback: "bars",
  colorMode: "auto",
  barSize: 1,
  intensity: 1,
  thickness: 1,
  brightness: 1,
  sensitivity: 1,
  top: true,
  bottom: true,
  left: true,
  right: true,
  onlyOverMusicApp: false,
};

function readEdgeCustomColors() {
  try {
    return localStorage.getItem(EDGE_CUSTOM_COLORS_KEY) || EDGE_DEFAULT_COLORS;
  } catch (err) {
    return EDGE_DEFAULT_COLORS;
  }
}

function rememberEdgeCustomColors(csv) {
  try {
    localStorage.setItem(EDGE_CUSTOM_COLORS_KEY, csv);
  } catch (err) {
    /* see readEdgeCustomColors() */
  }
}

function readEdgeSettingsFromForm() {
  return {
    style: els.edgeStyle.value,
    band: els.edgeBand.value,
    cocoonFallback: els.edgeCocoonFallback.value,
    colorMode: els.edgeColorMode.value,
    customColors: [els.edgeColor1.value, els.edgeColor2.value, els.edgeColor3.value].join(","),
    barSize: parseFloat(els.edgeBarSize.value),
    intensity: parseFloat(els.edgeIntensity.value),
    thickness: parseFloat(els.edgeThickness.value),
    brightness: parseFloat(els.edgeBrightness.value),
    sensitivity: parseFloat(els.edgeSensitivity.value),
    top: els.edgeTop.checked,
    bottom: els.edgeBottom.checked,
    left: els.edgeLeft.checked,
    right: els.edgeRight.checked,
    onlyOverMusicApp: els.edgeOnlyMusicApp.checked,
  };
}

function applyEdgeSettingsToForm(config) {
  els.edgeStyle.value = config.style;
  els.edgeBand.value = config.band;
  els.edgeCocoonFallback.value = config.cocoonFallback;
  els.edgeColorMode.value = config.colorMode;
  const colors = (config.customColors || EDGE_DEFAULT_COLORS).split(",");
  if (colors[0]) els.edgeColor1.value = colors[0];
  if (colors[1]) els.edgeColor2.value = colors[1];
  if (colors[2]) els.edgeColor3.value = colors[2];
  els.edgeBarSize.value = config.barSize;
  els.edgeIntensity.value = config.intensity;
  els.edgeThickness.value = config.thickness;
  els.edgeBrightness.value = config.brightness;
  els.edgeSensitivity.value = config.sensitivity;
  els.edgeTop.checked = config.top;
  els.edgeBottom.checked = config.bottom;
  els.edgeLeft.checked = config.left;
  els.edgeRight.checked = config.right;
  els.edgeOnlyMusicApp.checked = config.onlyOverMusicApp;
  els.edgeCustomColors.hidden = config.colorMode !== "custom";
}

/** Reads the form and pushes it to native — called on every settings-panel edit. */
function pushEdgeConfig() {
  const values = readEdgeSettingsFromForm();
  els.edgeCustomColors.hidden = values.colorMode !== "custom";
  rememberEdgeCustomColors(values.customColors);
  DeezerMedia.setEdgeConfig(values).catch(() => {});
}

/** Loads the panel's initial values: the numeric/boolean/style fields from native EdgeConfig
 *  (the actual source of truth for what the overlay renders), the color swatches from
 *  localStorage (native only stores them as parsed RGB, not the original hex strings). */
async function loadEdgeConfig() {
  let native = {};
  try {
    native = await DeezerMedia.getEdgeConfig();
  } catch (err) {
    // Older native build, or the plugin call failed: fall back to defaults below.
  }
  applyEdgeSettingsToForm({
    ...EDGE_SETTINGS_DEFAULTS,
    ...native,
    customColors: readEdgeCustomColors(),
  });
  // Not part of EdgeConfig: the on/off state lives in EdgeOverlayPreference and is tracked here
  // by edgeOverlayEnabled. Greyed out where TYPE_APPLICATION_OVERLAY doesn't exist at all
  // (below Android 8), where there is nothing to turn on.
  els.edgeOverlayEnabled.checked = edgeOverlayEnabled;
  els.edgeOverlayEnabled.disabled = !overlaySupported;
}

let edgeSettingsCloseTimer = null;

function openEdgeSettingsSheet() {
  clearTimeout(edgeSettingsCloseTimer);
  els.edgeSettingsSheet.hidden = false;
  requestAnimationFrame(() => {
    els.edgeSettingsSheet.classList.add("is-open");
    focusForRemote(els.edgeSettingsClose);
  });
}

function closeEdgeSettingsSheet() {
  els.edgeSettingsSheet.classList.remove("is-open");
  edgeSettingsCloseTimer = setTimeout(() => {
    els.edgeSettingsSheet.hidden = true;
  }, 260);
}

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
  // Leaving cassette mode with its buttons tapped away shouldn't carry that into the next
  // mode, or the next time cassette mode itself is picked again.
  if (displayMode !== "cassette") document.body.classList.remove("cassette-controls-hidden");
}

// No display mode forces the phone into a particular orientation — cassette mode used to lock
// landscape the way a video player forces landscape for fullscreen, but that fought the phone's
// own rotation: held upright (its normal, expected orientation, same as every other mode), the
// app switcher showed cassette mode's card sideways, and getting back to portrait meant leaving
// the app or physically turning the phone. Cassette mode is drawn cassette-side up (landscape)
// regardless: the CSS rotation trick (see @media (orientation: portrait) on .cassette__art in
// style.css) turns the illustration itself upright when the phone is, exactly like a Walkman
// held in the hand rather than propped up sideways.
// Guarded against re-firing on every applyDisplayMode(false) call (nowPlayingChanged fires
// that often) so the native side isn't asked to re-apply the same orientation repeatedly.
let orientationLockedFor = null;
function syncOrientationLock() {
  // A TV never rotates, so it never needs "unspecified" — and on at least one box, leaving it
  // unspecified let Android pick that device's *reversed* landscape as the natural one,
  // rendering the whole UI upside down (see the fixed, non-reversed LANDSCAPE used below, same
  // fix as lockLandscape() itself already applies natively).
  if (isTv) {
    if (orientationLockedFor === "tv") return;
    orientationLockedFor = "tv";
    DeezerMedia.lockLandscape().catch(() => {});
    return;
  }
  if (orientationLockedFor === "unlocked") return;
  orientationLockedFor = "unlocked";
  // No native implementation on the web (dev preview): fails silently there.
  DeezerMedia.unlockOrientation().catch(() => {});
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
els.overlayStatus.addEventListener("click", toggleEdgeOverlay);
els.overlayAccept.addEventListener("click", () => {
  closeOverlaySheet();
  rememberOverlaySheetSeen();
  DeezerMedia.requestOverlayPermission().catch(() => {});
});
els.overlayLater.addEventListener("click", closeOverlaySheet);
els.overlaySheet.addEventListener("click", (event) => {
  if (event.target === els.overlaySheet) closeOverlaySheet();
});

els.edgeSettingsOpen.addEventListener("click", () => {
  loadEdgeConfig()
    .then(syncUsageAccess)
    .then(openEdgeSettingsSheet);
});
els.edgeSettingsClose.addEventListener("click", closeEdgeSettingsSheet);
els.edgeSettingsSheet.addEventListener("click", (event) => {
  if (event.target === els.edgeSettingsSheet) closeEdgeSettingsSheet();
});
els.edgeOverlayEnabled.addEventListener("change", () => {
  // Same guard toggleEdgeOverlay() has. The disabled attribute set in loadEdgeConfig() is only
  // refreshed when the panel opens, so it can be stale if support is re-evaluated on a resume
  // while the panel is still on screen — without this, an unsupported device could still persist
  // "on" natively.
  if (!overlaySupported) {
    els.edgeOverlayEnabled.checked = false;
    return;
  }
  setEdgeOverlayEnabled(els.edgeOverlayEnabled.checked);
});
els.edgeUsageGrant.addEventListener("click", () => {
  DeezerMedia.requestUsageAccess().catch(() => {});
});

els.edgeColorMode.addEventListener("change", () => {
  els.edgeCustomColors.hidden = els.edgeColorMode.value !== "custom";
  pushEdgeConfig();
});
[
  els.edgeStyle,
  els.edgeBand,
  els.edgeCocoonFallback,
  els.edgeColor1,
  els.edgeColor2,
  els.edgeColor3,
  els.edgeBarSize,
  els.edgeIntensity,
  els.edgeThickness,
  els.edgeBrightness,
  els.edgeSensitivity,
  els.edgeTop,
  els.edgeBottom,
  els.edgeLeft,
  els.edgeRight,
  els.edgeOnlyMusicApp,
].forEach((el) => {
  el.addEventListener("input", pushEdgeConfig);
  el.addEventListener("change", pushEdgeConfig);
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
  const isTap =
    !cancelled && !g.dragging && performance.now() - g.t0 < TAP_MAX_MS && Math.abs(g.dy) < TAP_SLOP_PX;
  // A tap on the artwork still cycles visualizations — the obvious gesture on a screen you
  // look at from across the room, and the toast names what you landed on.
  if (isTap && g.onStage) {
    cycleDisplayMode();
  } else if (isTap && displayMode === "cassette") {
    // Cassette mode has no stage to tap (the artwork fills the screen): tapping it instead
    // toggles the transport buttons, scrub bar and title/artist card out of the way, for a
    // fully unobstructed view of the cassette (see .cassette-controls-hidden in style.css).
    document.body.classList.toggle("cassette-controls-hidden");
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
    focusForRemote(els.playPause);
  } else {
    visualizer.stop();
    visualizer.clear();
    // Nothing to release: the capture is owned natively and shared with the edge overlay, which
    // needs it exactly when this screen isn't showing.
    // Leave the ambient layer at rest rather than frozen mid-pulse.
    writeVar("--beat", "beat", 0);
    writeVar("--level", "level", 0);
    writeVar("--bass", "bass", 0);
    if (screen === "permission") focusForRemote(els.grantAccess);
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
  // Plain attribute, not backgroundImage: it's an <image> inside the cassette's inline SVG,
  // set as though it had been printed on the label — see .cassette__art-image in style.css.
  els.cassetteArt.setAttribute("href", art || "");

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
    syncEdgeOverlay();
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
  syncEdgeOverlay();

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

// Whether notification access was already seen granted, so the first time it flips from false
// to true this session can be told apart from "still granted, as it already was". Some devices
// grant that access through a path (see the fallback in requestPermission()) that doesn't
// actually rebind NowPlayingListenerService the way the standard settings toggle does, leaving
// it connected to nothing until something nudges it — see requestListenerRebind() below.
let notificationAccessKnownGranted = false;

async function refresh() {
  const { granted } = await DeezerMedia.checkPermission();
  if (!granted) {
    notificationAccessKnownGranted = false;
    showScreen("permission");
    return;
  }
  if (!notificationAccessKnownGranted) {
    notificationAccessKnownGranted = true;
    DeezerMedia.requestListenerRebind().catch(() => {});
  }
  const state = await DeezerMedia.getNowPlaying();
  setNowPlaying(state);
}

/* ------------------------------------------------------------------ wiring */

/**
 * A toast alone isn't enough for either case below: it fades before someone stuck on this exact
 * screen has necessarily read it, and they're going to be looking right at the screen it's on.
 */
function setPermissionHint(text) {
  els.permissionHint.textContent = text;
  els.permissionHint.hidden = !text;
}

els.grantAccess.addEventListener("click", () => {
  // Some Android TV builds have no notification-listener screen for the native side to open at
  // all (see requestPermission() in DeezerMediaPlugin.java): it either falls back to the root
  // Settings screen ({fallback: true} — the exact spot is now on the user to find) or, on the
  // rare device with no Settings app to open either, rejects outright.
  DeezerMedia.requestPermission()
    .then((result) => {
      if (result && result.fallback) {
        setPermissionHint(
          "Cet appareil ne propose pas de raccourci direct : dans Réglages, cherchez " +
            "Applications › Accès aux notifications, puis autorisez Vizuzik."
        );
      } else {
        setPermissionHint("");
      }
    })
    .catch(() => {
      setPermissionHint("Cet appareil ne propose pas ce réglage : Vizuzik ne peut pas l'autoriser ici.");
    });
});

els.recheckAccess.addEventListener("click", async () => {
  const { granted } = await DeezerMedia.checkPermission();
  if (granted) {
    setPermissionHint("");
    refresh().catch(() => {});
  } else {
    setPermissionHint(
      "Toujours pas détecté. Vérifiez que « Vizuzik » est bien coché dans la liste des accès " +
        "aux notifications, puis réessayez."
    );
  }
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
  if (data && data.levels) visualizer.setLevels(data.levels);
});
DeezerMedia.addListener("audioCaptureStopped", () => {
  // The music app closed its audio session. Nothing to reconcile — the visualizer drops back to
  // ambient on its own once levels stop arriving.
});

document.addEventListener("visibilitychange", () => {
  if (document.visibilityState === "visible") {
    // Resume the animation right away, using whatever state is already known — never wait on the
    // native round-trip below (refresh()) to do it. That round-trip being slow, or its promise
    // rejecting for any reason, must never leave the canvas frozen on whatever frame it had when
    // the app was backgrounded, with levels still arriving and nothing drawing them.
    if (!els.player.hidden) visualizer.start();
    refresh().catch(() => {});
    syncAudioPermission();
    syncOverlayPermission();
    syncUsageAccess();
  } else {
    // Nothing to animate against a hidden screen; rAF would be throttled anyway, but this
    // also drops the offscreen buffers' work entirely.
    visualizer.stop();
    // The capture keeps running: this is the one moment the overlay is allowed to exist, and it
    // draws from the very same levels this screen was using a moment ago.
    syncEdgeOverlay();
  }
});

window.addEventListener("resize", scheduleFocusRefresh);

applyDisplayMode(false);
(async () => {
  // Settled before anything renders: showScreen()/askMusicApp() below use isTv to decide
  // whether to plant the initial D-pad focus.
  await detectTvPlatform();
  // Which app to follow has to be settled first: the native now-playing listener needs it
  // before refresh() can report anything meaningful.
  const app = await resolveMusicApp();
  if (app) {
    // Awaited: refresh() below reads the now-playing session natively, and that lookup needs
    // to already know which app to look for.
    await DeezerMedia.setMusicAppTarget({ app }).catch(() => {});
  }
  await refresh().catch(() => {});
  requestAudioPermission();
  syncOverlayPermission();
  syncUsageAccess();
  loadEdgeConfig();
  // Cold-start mirror: EdgeOverlayPreference only remembers what setEdgeOverlayEnabled() last
  // wrote, and until now that only ever happened inside toggleEdgeOverlay() — someone who turned
  // Edge Visualizer on in an earlier session, then reinstalled the app or rebooted the phone
  // without touching the badge again, would have a native flag still stuck at its default
  // (false), so EdgeOverlayController could never start the overlay on its own from a track
  // starting in Deezer — exactly the "Vizuzik never opened this session" case it exists for.
  DeezerMedia.setEdgeOverlayEnabled({ enabled: edgeOverlayEnabled }).catch(() => {});
  // Cold start only: never repeated on a later resume, since by then a resumed session is
  // already exactly where it should be, and redoing this mid-session would restart a track the
  // user is deliberately listening to or pausing.
  //
  // Deliberately does NOT open Deezer/Spotify itself when there is no session at all — Vizuzik
  // is a companion display, not something that switches the user to another app on its own; the
  // "empty" screen simply waits until a track starts there by itself.
  if (app && !els.player.hidden && !isPlaying) {
    // A session for the tracked app is already there (it kept running in the background, still
    // holding the last track) — just paused. Resuming it straight from here means Vizuzik never
    // has to leave the screen at all.
    DeezerMedia.play().catch(() => {});
  }
})();
