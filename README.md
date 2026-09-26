# Vizuzik

Application Android plein écran affichant la lecture en cours sur Deezer ou Spotify (pochette,
titre, artiste) avec les contrôles précédent / lecture-pause / suivant, et un moteur visuel qui
réagit à la musique.

L'app lit la session multimédia de Deezer ou de Spotify (au choix, résolu au premier lancement en
cas d'ambiguïté) via l'API Android de notification listener : au premier lancement, elle demande
d'autoriser l'accès aux notifications (Réglages > Accès aux notifications) pour pouvoir afficher
et contrôler la lecture.

## Le visuel

Dix modes, en boucle via le bouton en haut à droite ou en touchant la pochette : **Pochette**,
**Spectre**, **Corona**, **Aurore**, **Nébuleuse**, **Cocon**, **Cassette**, **K7 Étiquette**,
**K7 Classique**, **Baladeur**. Les couleurs de
toute l'interface sont extraites de la pochette du morceau en cours, et la lueur pulse sur les
basses.

Sans capture du son, l'application n'invente aucun rythme : le disque tourne, les scènes
coulent sur des vagues lentes, les couleurs voyagent d'un accent de la pochette au suivant, et
l'écran ne s'illumine que sur un évènement réel (changement de morceau, lecture/pause,
glissement). Pour que les visualisations suivent réellement le son, appuyer sur le badge en
haut à gauche — voir [Une seule source audio](docs/architecture/2026-09-07-source-audio-unique.md).

## Edge Visualizer

Un second badge, à côté de celui du son réel, allume un contour lumineux dessiné **par-dessus
l'app de musique elle-même** (Deezer, Spotify, YouTube Music, un lecteur local...) — façon MuViz
Edge — dès qu'un morceau joue et que Vizuzik n'est pas à l'écran. Purement décoratif (aucun geste
n'est jamais capté), il s'éteint tout seul dès qu'on revient sur Vizuzik ou que la lecture
s'arrête. L'icône réglages à côté du badge ouvre le panneau : style, fréquences utilisées,
intensité, épaisseur, luminosité, sensibilité, couleurs (auto depuis la pochette ou fixes), et
l'activation de chacun des quatre bords. Voir [Edge Visualizer, sans micro
cette fois](docs/architecture/2026-09-06-edge-visualizer.md).

## Contrôles

Barre de progression avec temps écoulé, durée et déplacement dans le morceau. Sur la pochette :
**taper** change de visualisation, **glisser à gauche ou à droite** change de morceau — voir
[Gestes et contrôles](docs/guides/gestes.md).

## Permissions Android

Déclarées dans `android/app/src/main/AndroidManifest.xml` :

- **Accès aux notifications** (`BIND_NOTIFICATION_LISTENER_SERVICE`, accordé depuis Réglages, pas
  au sens `<uses-permission>`) — lit la session multimédia de Deezer/Spotify (titre, artiste,
  pochette, état de lecture) et lui envoie les contrôles précédent/lecture-pause/suivant.
- `RECORD_AUDIO` — attache `android.media.audiofx.Visualizer` à la session audio de l'app de
  musique elle-même ; aucune capture micro n'a jamais lieu (voir
  [Une seule source audio](docs/architecture/2026-09-07-source-audio-unique.md)).
- `SYSTEM_ALERT_WINDOW` — dessine le contour lumineux de l'Edge Visualizer par-dessus l'app de
  musique.
- `PACKAGE_USAGE_STATS` (accordé depuis Réglages) — détecte quelle app est au premier plan, pour
  l'option « seulement sur l'écran du lecteur » de l'Edge Visualizer.
- `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_SPECIAL_USE` — service au premier plan qui fait vivre
  l'overlay de l'Edge Visualizer.
- `POST_NOTIFICATIONS` (Android 13+) et `USE_FULL_SCREEN_INTENT` — affichent l'écran de
  visualisation par-dessus le vrai écran verrouillé (voir
  [Visualiseur écran verrouillé](docs/architecture/2026-09-09-visualiseur-ecran-verrouille.md)).
- `INTERNET` — requis par la WebView Capacitor.
- Accessibilité (`BIND_ACCESSIBILITY_SERVICE`, service `DeezerPlayerAccessibilityService`,
  **désactivé par défaut**) — lit uniquement l'écran du lecteur Deezer/Spotify, pour le mode
  « Seulement sur l'écran du lecteur ».
- `<queries>` sur `deezer.android.app` et `com.spotify.music`, plus les apps avec une icône de
  lancement — détection des apps installées (choix Deezer/Spotify, liste à masquer
  automatiquement).

## Développement

```bash
npm install
npm run dev          # serveur Vite
npm run build        # build web
npm run cap:sync     # build + synchronisation Capacitor vers android/
npm run android:open # ouvre le projet Android Studio
```

### Compiler l'APK Android

```bash
npm run cap:sync                     # www/dist à jour avant tout build natif
cd android
./gradlew assembleDebug              # APK signé avec la clé debug committée (android/app/debug.keystore)
./gradlew assembleRelease            # idem : aucune clé de production n'est configurée pour l'instant
```

Sans `-PvizuzikVersionCode=…  -PvizuzikVersionName=…` (voir plus bas), un build local retombe sur
`versionCode=1` / `versionName="1.0"`.

## Releases GitHub

`.github/workflows/android.yml` construit l'APK (`assembleDebug`, seule variante buildée en CI ;
il n'y a pas encore de clé de production, voir plus haut) à chaque push et publie une vraie
release GitHub à chaque push sur `main` :

- tag `v1.0.<run_number>` (le numéro de run GitHub Actions, donc strictement croissant à chaque
  publication) ;
- asset `Vizuzik-1.0.<run_number>.apk`, signé avec la clé debug committée — stable d'une release à
  l'autre.

Les push sur `staging`, les pull requests et les déclenchements manuels publient à la place une
pre-release roulante `debug-<branche>` (retag à chaque run, pas destinée à la distribution).

## Documentation

Voir [`docs/`](docs/README.md) : décisions d'architecture, API interne (`Visualizer`,
`extractPalette`) et guides d'utilisation.

## Licence

GNU General Public License v3.0 (GPL-3.0) — voir [`LICENSE`](LICENSE).

Exception : les deux polices manuscrites des modes K7, dans `public/fonts/`, sont des œuvres
tierces qui gardent leur propre licence — Permanent Marker (Apache 2.0) et Reenie Beanie (SIL Open
Font License 1.1). Leurs fichiers de licence les accompagnent jusque dans l'APK ; voir
[`public/fonts/README.md`](public/fonts/README.md) pour ce que ces licences impliquent.
