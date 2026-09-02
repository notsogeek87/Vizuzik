# Vizuzik

Lecteur musical Android façon Winamp, moderne, pour la musique locale — **V0.1**.

Pensé pour qu'une source Deezer officielle puisse être ajoutée plus tard sans réécrire
l'application (voir [Prêt pour Deezer](#prêt-pour-une-future-source-deezer)). **Aucune
intégration Deezer n'existe dans cette version.**

## Stack

`minSdk 26` (Android 8.0+) · `compileSdk`/`targetSdk 36` · AGP 8.13.0 · Gradle 8.14.3 · JDK 17/21.

| Brique | Version |
|---|---|
| Kotlin | 2.0.21 — **pas 2.1.x** : kapt (Room/Hilt) ne lit pas encore les métadonnées 2.1 |
| Compose (BOM) | 2024.12.01 + Material 3 |
| Media3 / ExoPlayer | 1.5.0 (`media3-exoplayer`, `media3-session`) |
| Hilt | 2.52 |
| Room | 2.6.1 |
| DataStore Preferences | 1.1.1 |
| Coroutines | 1.9.0 |
| Coil (pochettes) | 2.7.0 |

Versions volontairement conservatrices et vérifiées en CI ; à monter d'un bloc lors
d'une prochaine itération (passer de kapt à KSP débloquerait Kotlin 2.1+).

## Architecture

Clean Architecture / MVVM, avec la bibliothèque musicale abstraite derrière une
interface `MusicSource` pour permettre une future source distante (Deezer) sans
toucher à l'UI ni au lecteur :

```
domain/source/MusicSource (interface) ← data/source/LocalMusicSource (V0.1, MediaStore)
domain/player/MusicPlayer (interface) ← player/Media3MusicPlayer (Media3/ExoPlayer)
domain/repository/MusicRepository     ← data/repository/MusicRepositoryImpl (cache Room)
```

```
app/src/main/java/com/vizuzik/app/
  domain/            modèles, interfaces (MusicSource, MusicPlayer), use cases, agrégation
  data/              MediaStore, cache Room, playlists, préférences (DataStore)
  player/            Media3MusicPlayer (MediaController) + PlaybackService (MediaSession)
  audio/             AudioEngine + Equalizer/BassBoost/Virtualizer/Reverb (android.media.audiofx)
  visualizer/        Visualizer (FFT) + barres de spectre Compose
  theme/             PlayerTheme + 3 skins (Modern, Winamp Classic, Dark)
  di/                modules Hilt
  ui/                navigation, mini-player, écrans (home/tracks/albums/artists/playlists/player/...)
```

L'UI ne dépend jamais d'ExoPlayer : seulement de `MusicPlayer`. La couche
bibliothèque ne dépend jamais de `MediaStore` en dehors de `LocalMusicSource`.

## Fonctionnalités V0.1

- Scan de la musique locale (`MediaStore`), mis en cache dans Room — pas de rescan
  à chaque ouverture, bouton "Actualiser la bibliothèque" pour forcer.
- Accueil, Morceaux (tri + recherche), Albums, Artistes, Playlists (créer / renommer /
  supprimer / ajouter / retirer / réordonner).
- Mini-player persistant + écran lecteur plein écran (pochette, progression, shuffle,
  repeat, file d'attente).
- File d'attente : lecture, réorganisation, suppression, vidage.
- Lecture en arrière-plan via `MediaSessionService`, notification média, contrôles
  Bluetooth/écouteurs et écran verrouillé gérés par Media3.
- Égaliseur 5 bandes + Bass Boost + Virtualizer réels (`android.media.audiofx`, pas de
  simulation par le volume).
- Visualiseur : analyseur de spectre simple (FFT via `Visualizer`).
- 3 skins (Modern, Winamp Classic, Dark), changement dynamique et persisté.
- Recherche insensible à la casse/aux accents.

### Limites connues

- Les tests instrumentés Compose (`androidTest/`) sont écrits mais pas exécutés en CI :
  ça demanderait un émulateur dans le workflow.
- Les pochettes passent par l'URI historique `content://media/external/audio/albumart/<id>`,
  avec repli sur une icône quand elle est absente ou refusée par le système.
- APK signé avec la clé de debug uniquement — pas de clé de release configurée.

### Volontairement laissé pour une itération suivante

- Presets d'égaliseur, EQ 10 bandes/paramétrique, balance, preamp, Reverb en UI
  (l'architecture `AudioProcessor` les supporte déjà — `PresetReverbProcessor` existe).
- Visualisations avancées (waveform, VU-meters, oscilloscope, plein écran, style MilkDrop).
- Glisser-déposer pour réordonner (implémenté via boutons haut/bas pour l'instant).
- Tests instrumentés exécutés en CI (écrits, mais nécessitent un émulateur).

## Compiler

```bash
cd android
./gradlew assembleDebug
```

L'APK debug est signé avec la clé de debug committée (`android/app/debug.keystore`,
mot de passe `android` — standard, non secret) pour des builds CI reproductibles.

## Installer

```bash
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
```

Ou récupérer l'APK depuis les Releases GitHub / artefacts du workflow
`.github/workflows/android.yml` (build automatique à chaque push).

## Prêt pour une future source Deezer

- `MusicSource` (tracks/albums/artists/search) est déjà l'interface que
  `DeezerMusicSource` implémenterait.
- `Track`/`Album`/`Artist` portent un `sourceType` (`LOCAL`/`DEEZER`) et des
  identifiants préfixés par source (`local:123`), pensés pour cohabiter avec des
  identifiants Deezer sans collision.
- `MusicRepository` agrège déjà les sources par construction ; brancher Deezer
  n'implique pas de changement d'UI ni de player.
- Le lecteur (`MusicPlayer`) et l'UI ne connaissent que des `Track` du domaine —
  peu importe leur origine.

Aucun contournement des protections Deezer, aucune API privée, aucun flux non
officiel : ce dépôt ne prétend à aucun moment offrir une intégration Deezer
fonctionnelle.
