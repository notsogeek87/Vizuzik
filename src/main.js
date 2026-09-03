import { registerPlugin } from "@capacitor/core";

const DeezerMedia = registerPlugin("DeezerMedia");

const els = {
  background: document.getElementById("background"),
  player: document.getElementById("player"),
  cover: document.getElementById("cover"),
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

function showScreen(screen) {
  els.player.hidden = screen !== "player";
  els.empty.hidden = screen !== "empty";
  els.permission.hidden = screen !== "permission";
}

function setNowPlaying(state) {
  if (!state || !state.active) {
    showScreen("empty");
    els.background.style.backgroundImage = "";
    return;
  }

  showScreen("player");
  els.title.textContent = state.title || "Titre inconnu";
  els.artist.textContent = state.artist || "";

  isPlaying = !!state.isPlaying;
  els.playPause.dataset.state = isPlaying ? "playing" : "paused";

  const art = state.albumArt || "";
  els.cover.style.backgroundImage = art ? `url(${art})` : "";
  els.background.style.backgroundImage = art ? `url(${art})` : "";
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
