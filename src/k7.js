// Tape mechanics for the two K7 modes (see #k7 in index.html): how much tape is wound on each
// reel, the path the tape takes from one pack to the other, and the quick rewind when a new
// track starts.
//
// Driven from the same played ratio as the progress bar, but not tied to it frame for frame:
// a jump in playback — scrubbing, or a new track starting from zero with the right-hand reel
// still full — would otherwise make both packs snap to their new size in a single frame. A
// scrub glides there instead; a track change rewinds, the reels spinning back fast while the
// tape returns to the left-hand reel.

// In the illustration's own 320x200 viewBox units.
const REEL_A_X = 97;
const REEL_B_X = 223;
const REEL_Y = 84;
// A full pack: as big as the shell allows — the left reel's centre sits 84 units down, the
// shell's inner edge 12 — which on a smoked K7 Classique peeks out from under the label.
const PACK_FULL = 70;
// An empty pack: just the tape's leader round the hub.
const PACK_EMPTY = 14;

// One rewind turn, in seconds — the same period as .k7__rewind's animation in style.css. A
// rewind always lasts a whole number of these turns, so the reels end exactly where they began.
const REWIND_TURN_S = 0.3;
// Rewinding a whole side takes this long; less tape, proportionally less (never under 2 turns).
const REWIND_FULL_S = 1.3;

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
    this.reducedMotion = window.matchMedia("(prefers-reduced-motion: reduce)");
    this.shown = null;
    this.written = null;
    this.lastAt = 0;
    this.rewind = null;
  }

  /** A new track has started: rewind whatever was played of the last one. */
  trackChanged() {
    if (this.shown == null || this.shown < JUMP || this.reducedMotion.matches) return;
    const turns = Math.max(2, Math.round((REWIND_FULL_S * this.shown) / REWIND_TURN_S));
    this.rewind = { from: this.shown, startedAt: performance.now(), durationMs: turns * REWIND_TURN_S * 1000 };
    this.root.style.setProperty("--k7-rewind-turns", String(turns));
    // Restarted from its first turn even if a rewind was already under way (tracks skipped
    // in quick succession).
    this.root.classList.remove("is-rewinding");
    void this.root.getBoundingClientRect();
    this.root.classList.add("is-rewinding");
  }

  /** Called on every animation frame with how far into the track playback is, 0..1. */
  update(played) {
    const now = performance.now();
    const dt = Math.min(0.1, (now - this.lastAt) / 1000);
    this.lastAt = now;

    if (this.rewind) {
      const t = Math.min(1, (now - this.rewind.startedAt) / this.rewind.durationMs);
      const eased = t < 0.5 ? 2 * t * t : 1 - (2 - 2 * t) ** 2 / 2;
      this.shown = this.rewind.from * (1 - eased);
      if (t >= 1) {
        this.rewind = null;
        this.root.classList.remove("is-rewinding");
      }
    } else if (this.shown == null || Math.abs(played - this.shown) < JUMP) {
      this.shown = played;
    } else {
      this.shown += (played - this.shown) * Math.min(1, dt * GLIDE_RATE);
    }
    this.draw();
  }

  draw() {
    if (this.written != null && Math.abs(this.shown - this.written) < 0.0005) return;
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
