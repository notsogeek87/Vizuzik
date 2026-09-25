// Mechanics for the two K7 modes (see #k7 in index.html): K7Tape — the reels turning, how much
// tape is wound on each, the path the tape takes from one pack to the other, and the quick rewind
// when a new track starts — and K7Lid, the Walkman lid that carries their controls.
//
// Driven from the same played ratio as the progress bar, but not tied to it frame for frame:
// a jump in playback — scrubbing, or a new track starting from zero with the right-hand reel
// still full — would otherwise make both packs snap to their new size in a single frame. A
// scrub glides there instead; a track change rewinds, the reels spinning back fast while the
// tape returns to the left-hand reel.

import { formatTime } from "./progress.js";

// In the illustration's own 320x200 viewBox units.
const REEL_A_X = 97;
const REEL_B_X = 223;
const REEL_Y = 84;
// A full pack: as big as the shell allows — the left reel's centre sits 84 units down, the
// shell's inner edge 12 — which on a smoked K7 Classique peeks out from under the label.
const PACK_FULL = 70;
// An empty pack: just the tape's leader round the hub.
const PACK_EMPTY = 14;

// One turn while playing, in seconds, for each reel: slightly different, as tape winds from one
// to the other (the same two periods as cassette mode's reels). Both turn anticlockwise, as on a
// real deck: the tape leaves the left pack by its outer edge and winds onto the right one by its.
const PLAY_TURN_S = [3.2, 3.8];
// One turn while rewinding, the other way round, and how long rewinding a whole side takes (less
// tape, proportionally less, never under two turns' worth).
const REWIND_TURN_S = 0.3;
const REWIND_FULL_S = 1.3;
const REWIND_MIN_S = 0.6;

// Below this, a difference is just playback moving on; above it, it's a jump to glide over.
const JUMP = 0.02;
// How fast a scrub glides to its new position: about half a second.
const GLIDE_RATE = 8;

export class K7Tape {
  constructor(root) {
    this.root = root;
    this.packsA = root.querySelectorAll(".k7__pack--a");
    this.packsB = root.querySelectorAll(".k7__pack--b");
    this.tape = root.querySelector(".k7__tape");
    this.reels = [
      { el: root.querySelector(".k7__hub--a"), x: REEL_A_X, angle: 0, written: null },
      { el: root.querySelector(".k7__hub--b"), x: REEL_B_X, angle: 0, written: null },
    ];
    this.reducedMotion = window.matchMedia("(prefers-reduced-motion: reduce)");
    this.shown = null;
    this.written = null;
    this.lastAt = 0;
    this.rewind = null;
  }

  /** A new track has started: rewind whatever was played of the last one. */
  trackChanged() {
    if (this.shown == null || this.shown < JUMP || this.reducedMotion.matches) return;
    const seconds = Math.max(REWIND_MIN_S, REWIND_FULL_S * this.shown);
    this.rewind = { from: this.shown, startedAt: performance.now(), durationMs: seconds * 1000 };
  }

  /**
   * Called on every animation frame with how far into the track playback is, 0..1, and whether
   * it is playing (the reels only turn then, or while rewinding).
   */
  update(played, playing) {
    const now = performance.now();
    const dt = Math.min(0.1, (now - this.lastAt) / 1000);
    this.lastAt = now;

    if (this.rewind) {
      const t = Math.min(1, (now - this.rewind.startedAt) / this.rewind.durationMs);
      const eased = t < 0.5 ? 2 * t * t : 1 - (2 - 2 * t) ** 2 / 2;
      this.shown = this.rewind.from * (1 - eased);
      if (t >= 1) this.rewind = null;
    } else if (this.shown == null || Math.abs(played - this.shown) < JUMP) {
      this.shown = played;
    } else {
      this.shown += (played - this.shown) * Math.min(1, dt * GLIDE_RATE);
    }
    this.turnReels(dt, playing);
    this.draw();
  }

  turnReels(dt, playing) {
    if (this.reducedMotion.matches || (!playing && !this.rewind)) return;
    this.reels.forEach((reel, i) => {
      // Anticlockwise while playing (a negative angle, screen y pointing down); clockwise, fast,
      // while rewinding.
      reel.angle = (reel.angle + (this.rewind ? 360 / REWIND_TURN_S : -360 / PLAY_TURN_S[i]) * dt) % 360;
      const angle = reel.angle.toFixed(1);
      if (angle === reel.written) return;
      reel.written = angle;
      reel.el.setAttribute("transform", `rotate(${angle} ${reel.x} ${REEL_Y})`);
    });
  }

  draw() {
    // Every write repaints the illustration: skip the ones too small to see (a tenth of a unit).
    if (this.written != null && Math.abs(this.shown - this.written) < 0.002) return;
    this.written = this.shown;
    const packA = PACK_FULL - this.shown * (PACK_FULL - PACK_EMPTY);
    const packB = PACK_EMPTY + this.shown * (PACK_FULL - PACK_EMPTY);
    for (const el of this.packsA) el.setAttribute("r", packA.toFixed(2));
    for (const el of this.packsB) el.setAttribute("r", packB.toFixed(2));
    // Off each pack's outer edge, down round the corner guide rollers (centred at 40,175 and
    // 280,175, radius 10) and along the bottom edge, past the pressure pad.
    const leaveA = (REEL_A_X - packA).toFixed(2);
    const reachB = (REEL_B_X + packB).toFixed(2);
    this.tape.setAttribute(
      "d",
      `M${leaveA} ${REEL_Y} L29.6 174 Q29.6 185.4 40 185.4 L280 185.4 Q290.4 185.4 290.4 174 L${reachB} ${REEL_Y}`,
    );
  }
}

// The lid's controls, in the same viewBox units (see .k7__lid in index.html). Hit zones are a
// little larger than what's drawn, for a thumb.
const LID_KEYS = { previous: 114, "play-pause": 160, next: 206 };
const LID_KEY_HALF_WIDTH = 23;
const LID_KEY_TOP = 154;
const LID_KEY_BOTTOM = 194;
const RULER_START = 78;
const RULER_END = 242;
// Just the ruler's own strip across the window: a tap anywhere else on the glass still opens
// the lid rather than seeking.
const RULER_ZONE = { left: 62, right: 258, top: 86, bottom: 112 };

// How far, in degrees, a tilt keeps moving the glint before it stops; and how quickly the glint's
// rest position follows however the phone is being held, so it drifts back to centre.
const TILT_RANGE = 25;
const TILT_REST_FOLLOW = 0.005;
// A phone lying still still reports a little sensor noise: smoothed out, and below this many
// degrees of change the glint isn't redrawn at all.
const TILT_SMOOTHING = 0.25;
const TILT_DEADBAND = 0.4;

function clampTo(value, min, max) {
  return value < min ? min : value > max ? max : value;
}

export class K7Lid {
  constructor(root) {
    this.svg = root.querySelector(".k7__lid-layer");
    this.needle = root.querySelector(".k7__lid-needle");
    this.time = root.querySelector("#k7-lid-time");
    this.sweep = root.querySelector(".k7__lid-sweep");
    this.glints = root.querySelector(".k7__lid-glints");
    this.keys = {};
    for (const key of root.querySelectorAll(".k7__lid-key")) this.keys[key.dataset.key] = key;
    this.reducedMotion = window.matchMedia("(prefers-reduced-motion: reduce)");
    this.shownTime = null;
    this.shownNeedle = null;
    this.tiltRest = null;
    this.listening = false;
    this.glint = null;
    this.glintFrame = null;
    this.shownGlint = null;
    this.onOrientation = (event) => this._onOrientation(event);
  }

  /**
   * What a touch at this screen point lands on: a key, the ruler (with where along it), or
   * nothing. The illustration sits under .player, so it never gets the pointer itself; the
   * point is mapped into its own coordinates instead, turned a quarter when the phone is upright
   * and scaled to whatever the screen is.
   */
  hit(clientX, clientY) {
    const p = this._toViewBox(clientX, clientY);
    if (!p) return null;
    if (p.y >= LID_KEY_TOP && p.y <= LID_KEY_BOTTOM) {
      for (const [key, x] of Object.entries(LID_KEYS)) {
        if (Math.abs(p.x - x) <= LID_KEY_HALF_WIDTH) return { key };
      }
    }
    if (p.x >= RULER_ZONE.left && p.x <= RULER_ZONE.right && p.y >= RULER_ZONE.top && p.y <= RULER_ZONE.bottom) {
      return { ruler: this._rulerRatio(p) };
    }
    return null;
  }

  /** Where along the ruler a finger is, 0..1, wherever it has wandered to since. */
  rulerRatio(clientX, clientY) {
    const p = this._toViewBox(clientX, clientY);
    return p ? this._rulerRatio(p) : 0;
  }

  press(key, down) {
    if (this.keys[key]) this.keys[key].classList.toggle("is-pressed", down);
  }

  /** Called on every animation frame: the LCD's time and the ruler's needle. */
  update(positionMs, played) {
    const time = formatTime(positionMs);
    if (time !== this.shownTime) {
      this.shownTime = time;
      this.time.textContent = time;
    }
    // In half-unit steps: finer than the eye can follow on a needle, and far fewer repaints.
    const offset = (Math.round((RULER_END - RULER_START) * played * 2) / 2).toFixed(1);
    if (offset !== this.shownNeedle) {
      this.shownNeedle = offset;
      this.needle.setAttribute("transform", `translate(${offset} 0)`);
    }
  }

  /** Only listens to the motion sensors while the lid is actually shut on screen. */
  setActive(active) {
    const listen = active && !this.reducedMotion.matches && "DeviceOrientationEvent" in window;
    if (listen === this.listening) return;
    this.listening = listen;
    if (listen) {
      this.tiltRest = null;
      window.addEventListener("deviceorientation", this.onOrientation);
    } else {
      window.removeEventListener("deviceorientation", this.onOrientation);
      this.sweep.removeAttribute("transform");
      this.glints.removeAttribute("transform");
      this.shownGlint = null;
      this.glint = null;
    }
  }

  _toViewBox(clientX, clientY) {
    const matrix = this.svg.getScreenCTM();
    return matrix ? new DOMPoint(clientX, clientY).matrixTransform(matrix.inverse()) : null;
  }

  _rulerRatio(p) {
    return clampTo((p.x - RULER_START) / (RULER_END - RULER_START), 0, 1);
  }

  // Moves the light band across the lid's metal as the phone tilts, the way a real brushed
  // aluminium plate catches the light — and the window's glints with it, less far, so glass and
  // plate read as two planes rather than one flat picture.
  _onOrientation(event) {
    if (event.gamma == null || event.beta == null) return;
    // The sensor reports the device's own axes; turn them into the screen's.
    const angle = (screen.orientation && screen.orientation.angle) || 0;
    let x = event.gamma;
    let y = event.beta;
    if (angle === 90) [x, y] = [event.beta, -event.gamma];
    else if (angle === 180) [x, y] = [-event.gamma, -event.beta];
    else if (angle === 270) [x, y] = [-event.beta, event.gamma];
    if (!this.tiltRest) this.tiltRest = { x, y };
    this.tiltRest.x += (x - this.tiltRest.x) * TILT_REST_FOLLOW;
    this.tiltRest.y += (y - this.tiltRest.y) * TILT_REST_FOLLOW;
    const dx = clampTo(x - this.tiltRest.x, -TILT_RANGE, TILT_RANGE);
    const dy = clampTo(y - this.tiltRest.y, -TILT_RANGE, TILT_RANGE);
    // And the screen's into the illustration's: upright, it is turned a quarter clockwise.
    const upright = window.innerHeight > window.innerWidth;
    const target = { x: upright ? dy : dx, y: upright ? -dx : dy };
    if (!this.glint) this.glint = target;
    this.glint.x += (target.x - this.glint.x) * TILT_SMOOTHING;
    this.glint.y += (target.y - this.glint.y) * TILT_SMOOTHING;
    // The sensor fires faster than the screen redraws: at most one repaint per frame, and none
    // for a move too small to see.
    if (this.glintFrame == null) this.glintFrame = requestAnimationFrame(() => this._drawGlint());
  }

  _drawGlint() {
    this.glintFrame = null;
    if (!this.listening || !this.glint) return;
    const { x, y } = this.glint;
    if (this.shownGlint && Math.abs(x - this.shownGlint.x) < TILT_DEADBAND && Math.abs(y - this.shownGlint.y) < TILT_DEADBAND) {
      return;
    }
    this.shownGlint = { x, y };
    this.sweep.setAttribute("transform", `translate(${(x * 4).toFixed(1)} ${(y * 1.5).toFixed(1)})`);
    this.glints.setAttribute("transform", `translate(${(x * 2.4).toFixed(1)} ${(y * 0.9).toFixed(1)})`);
  }
}
