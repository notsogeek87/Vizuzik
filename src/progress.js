// Playback progress: the media session only reports a position when something changes, so the
// bar runs on a local clock anchored to the last known value and is re-anchored periodically
// (see the getPosition() poll in main.js). Rendering is throttled well below the frame rate —
// a bar that advances one pixel per second gains nothing from 60 updates a second.

const RENDER_INTERVAL_MS = 120;

function clamp(value, min, max) {
  return value < min ? min : value > max ? max : value;
}

/** "3:07", or "1:02:33" once past an hour. */
function formatTime(ms) {
  if (!Number.isFinite(ms) || ms < 0) ms = 0;
  const total = Math.floor(ms / 1000);
  const seconds = total % 60;
  const minutes = Math.floor(total / 60) % 60;
  const hours = Math.floor(total / 3600);
  const mm = hours > 0 ? String(minutes).padStart(2, "0") : String(minutes);
  return `${hours > 0 ? `${hours}:` : ""}${mm}:${String(seconds).padStart(2, "0")}`;
}

export class PlaybackProgress {
  /**
   * @param {{root: HTMLElement, bar: HTMLElement, elapsed: HTMLElement, total: HTMLElement}} els
   * @param {{onSeek: (positionMs: number) => void}} handlers
   */
  constructor(els, { onSeek }) {
    this.els = els;
    this.onSeek = onSeek;

    this.duration = 0;
    this.anchorMs = 0;
    this.anchorAt = performance.now();
    this.isPlaying = false;
    this.canSeek = false;

    this.scrubRatio = null;
    this.pointerId = null;

    this.lastRenderAt = 0;
    this.lastElapsedLabel = null;
    this.lastTotalLabel = null;

    els.bar.addEventListener("pointerdown", (e) => this._onPointerDown(e));
    els.bar.addEventListener("pointermove", (e) => this._onPointerMove(e));
    els.bar.addEventListener("pointerup", (e) => this._onPointerUp(e));
    els.bar.addEventListener("pointercancel", () => this._cancelScrub());
  }

  /**
   * Re-anchors the local clock. `position` is the position at the instant the native side
   * resolved it, so "now" is the right anchor timestamp.
   */
  setTrack({ position = 0, duration = 0, isPlaying = false, canSeek = false } = {}) {
    // A seek in flight would otherwise be undone by the pre-seek position still coming back
    // from the poll; the scrub itself is the more recent truth until it lands.
    if (this.scrubRatio != null) return;
    this.duration = Math.max(0, duration);
    this.anchorMs = clamp(position, 0, this.duration || position);
    this.anchorAt = performance.now();
    this.isPlaying = isPlaying;
    this.canSeek = canSeek && this.duration > 0;
    this.els.root.dataset.available = this.duration > 0 ? "true" : "false";
    this.els.root.dataset.seekable = this.canSeek ? "true" : "false";
    this.render(true);
  }

  positionNow() {
    if (this.scrubRatio != null) return this.scrubRatio * this.duration;
    const elapsed = this.isPlaying ? performance.now() - this.anchorAt : 0;
    return clamp(this.anchorMs + elapsed, 0, this.duration || Infinity);
  }

  /** Called from the visualizer's per-frame callback; throttles itself. */
  render(force) {
    const now = performance.now();
    if (!force && now - this.lastRenderAt < RENDER_INTERVAL_MS) return;
    this.lastRenderAt = now;

    const position = this.positionNow();
    const ratio = this.duration > 0 ? clamp(position / this.duration, 0, 1) : 0;
    this.els.root.style.setProperty("--p", ratio.toFixed(4));

    const elapsed = formatTime(position);
    if (elapsed !== this.lastElapsedLabel) {
      this.lastElapsedLabel = elapsed;
      this.els.elapsed.textContent = elapsed;
    }
    const total = this.duration > 0 ? formatTime(this.duration) : "--:--";
    if (total !== this.lastTotalLabel) {
      this.lastTotalLabel = total;
      this.els.total.textContent = total;
    }
  }

  /* ------------------------------------------------------------------ scrubbing */

  _ratioFromEvent(event) {
    const rect = this.els.bar.getBoundingClientRect();
    if (!rect.width) return 0;
    return clamp((event.clientX - rect.left) / rect.width, 0, 1);
  }

  _onPointerDown(event) {
    if (!this.canSeek || this.pointerId != null) return;
    this.pointerId = event.pointerId;
    this.els.bar.setPointerCapture(event.pointerId);
    this.els.root.classList.add("is-scrubbing");
    this.scrubRatio = this._ratioFromEvent(event);
    this.render(true);
    event.preventDefault();
  }

  _onPointerMove(event) {
    if (this.pointerId !== event.pointerId || this.scrubRatio == null) return;
    this.scrubRatio = this._ratioFromEvent(event);
    this.render(true);
  }

  _onPointerUp(event) {
    if (this.pointerId !== event.pointerId || this.scrubRatio == null) return;
    const target = this.scrubRatio * this.duration;
    this._releasePointer();
    // Anchored optimistically at the requested position: Deezer takes a moment to report the
    // new one, and a bar that snapped back before jumping forward would look broken.
    this.scrubRatio = null;
    this.anchorMs = target;
    this.anchorAt = performance.now();
    this.render(true);
    this.onSeek(Math.round(target));
  }

  _cancelScrub() {
    this._releasePointer();
    this.scrubRatio = null;
    this.render(true);
  }

  _releasePointer() {
    if (this.pointerId != null && this.els.bar.hasPointerCapture(this.pointerId)) {
      this.els.bar.releasePointerCapture(this.pointerId);
    }
    this.pointerId = null;
    this.els.root.classList.remove("is-scrubbing");
  }
}
