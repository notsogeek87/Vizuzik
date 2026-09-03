import { registerPlugin } from "@capacitor/core";
import { Visualizer } from "./visualizer.js";

const DeezerMedia = registerPlugin("DeezerMedia");

const els = {
  background: document.getElementById("background"),
  player: document.getElementById("player"),
  coverWrap: document.getElementById("cover-wrap"),
  cover: document.getElementById("cover"),
  visualizerCanvas: document.getElementById("visualizer"),
  modeToggle: document.getElementById("mode-toggle"),
  title: document.getElementById("title"),
  artist: document.getElementById("artist"),
  playPause: document.getElementById("play-pause"),
  previous: document.getElementById("previous"),
  next: document.getElementById("next"),
  empty: document.getElementById("empty"),
  permission: document.getElementById("permission"),
  grantAccess: document.getElementById("grant-access"),
};

let isPlaying = false;

const DISPLAY_MODE_KEY = "vizuzik:displayMode";
let displayMode = localStorage.getItem(DISPLAY_MODE_KEY) === "visualizer" ? "visualizer" : "cover";
const visualizer = new Visualizer(els.visualizerCanvas);

function applyDisplayMode() {
  els.coverWrap.dataset.mode = displayMode;
  if (displayMode === "visualizer" && !els.player.hidden) {
    visualizer.start();
  } else {
    visualizer.stop();
  }
}

els.modeToggle.addEventListener("click", () => {
  displayMode = displayMode === "cover" ? "visualizer" : "cover";
  localStorage.setItem(DISPLAY_MODE_KEY, displayMode);
  applyDisplayMode();
});

function showScreen(screen) {
  els.player.hidden = screen !== "player";
  els.empty.hidden = screen !== "empty";
  els.permission.hidden = screen !== "permission";
}

function setNowPlaying(state) {
  if (!state || !state.active) {
    showScreen("empty");
    els.background.style.backgroundImage = "";
    visualizer.stop();
    return;
  }

  showScreen("player");
  els.title.textContent = state.title || "Titre inconnu";
  els.artist.textContent = state.artist || "";

  isPlaying = !!state.isPlaying;
  els.playPause.dataset.state = isPlaying ? "playing" : "paused";
  visualizer.setPlaying(isPlaying);

  const art = state.albumArt || "";
  els.cover.style.backgroundImage = art ? `url(${art})` : "";
  els.background.style.backgroundImage = art ? `url(${art})` : "";
  if (art) visualizer.setColorFromImage(art);

  applyDisplayMode();
}

async function refresh() {
  const { granted } = await DeezerMedia.checkPermission();
  if (!granted) {
    showScreen("permission");
    return;
  }
  const state = await DeezerMedia.getNowPlaying();
  setNowPlaying(state);
}

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

document.addEventListener("visibilitychange", () => {
  if (document.visibilityState === "visible") {
    refresh();
  }
});

refresh();
