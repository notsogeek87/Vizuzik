# Vizuzik

Lecteur musical Android façon Winamp, moderne, pour la musique locale — **V0.1** — et
capable de piloter Deezer avec ce même skin (voir [Deezer](#deezer)).

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
interface `MusicSource` et le lecteur derrière `MusicPlayer`, ce qui a permis de
brancher Deezer (voir [Deezer](#deezer)) sans toucher à l'UI :

```
domain/source/MusicSource (interface) ← data/source/LocalMusicSource (MediaStore)
domain/player/MusicPlayer (interface) ← player/MusicPlayerRouter (délègue selon la source active)
                                           ├─ player/Media3MusicPlayer (local, Media3/ExoPlayer)
                                           └─ player/deezer/DeezerRemotePlayer (télécommande Deezer)
domain/repository/MusicRepository     ← data/repository/MusicRepositoryImpl (cache Room)
```

```
app/src/main/java/com/vizuzik/app/
  domain/            modèles, interfaces (MusicSource, MusicPlayer), use cases, agrégation
  data/              MediaStore, cache Room, playlists, préférences (DataStore)
  data/remote/deezer/ client OAuth + API Deezer (voir Deezer)
  player/            MusicPlayerRouter, Media3MusicPlayer (MediaController) + PlaybackService
  player/deezer/     DeezerRemotePlayer (télécommande de la session média Deezer)
  audio/             AudioEngine + Equalizer/BassBoost/Virtualizer/Reverb (android.media.audiofx)
  visualizer/        Visualizer (FFT) + barres de spectre Compose
  theme/             PlayerTheme + 3 skins (Modern, Winamp Classic, Dark)
  di/                modules Hilt
  diagnostics/       sonde MediaSession (développement/diagnostic Deezer)
  ui/                navigation, mini-player, écrans (home/tracks/albums/artists/playlists/player/deezer/...)
```

L'UI ne dépend jamais d'ExoPlayer ni de Deezer directement : seulement de
`MusicPlayer`. La couche bibliothèque ne dépend jamais de `MediaStore` en dehors
de `LocalMusicSource`.

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

## Deezer

Deezer ne propose aucun accord commercial de streaming complet pour une app tierce
(API publique limitée à 30 s d'extrait, SDK natif déprécié) : Vizuzik ne lit donc
jamais l'audio Deezer lui-même. À la place, il **télécommande la session média de
l'app Deezer officielle** (déjà installée sur l'appareil) via les API Android
standard (`MediaSessionManager`/`MediaController`), avec le skin choisi dans
Vizuzik. Validé en direct : `playFromSearch` lance fiablement un album/playlist
par son nom, sans garantie de précision au morceau près — largement suffisant
pour lancer un album ou une playlist qu'on a déjà sur Deezer.

Ce que ça permet :
- Se connecter à son compte Deezer (OAuth) et voir ses albums/playlists.
- Toucher un album/playlist l'envoie à l'app Deezer, qui doit avoir été ouverte
  au moins une fois auparavant sur l'appareil.
- Le mini-player et l'écran plein écran de Vizuzik reflètent alors ce que joue
  Deezer (titre, pochette, position, play/pause/suivant/précédent) avec le skin
  choisi.

Ce que ça ne permet pas :
- Choisir un morceau précis dans un album/playlist (recherche par nom, pas par
  identifiant) — accepté comme limite, voir l'historique des commits.
- Égaliseur ou visualiseur sur l'audio Deezer : Vizuzik ne possède pas ce flux,
  ces écrans sont masqués quand Deezer est la source active.
- Réordonner/modifier la file d'attente de Deezer depuis Vizuzik.

Aucun contournement des protections Deezer, aucune API privée, aucun flux non
officiel.

### Configuration (nécessaire pour que la connexion Deezer fonctionne)

1. Créer une app sur [developers.deezer.com/myapps](https://developers.deezer.com/myapps).
2. Dans les réglages de cette app, mettre comme **Domaine de redirection** :
   `vizuzik.local` (ou l'hôte de `deezerRedirectUri` si personnalisé — voir plus bas).
3. Noter l'**App ID** et le **Secret Key**.
4. Dans le dépôt GitHub → *Settings* → *Secrets and variables* → *Actions*, ajouter
   deux secrets : `DEEZER_APP_ID` et `DEEZER_APP_SECRET`, avec ces valeurs.
5. Relancer le workflow `.github/workflows/android.yml` (ou pousser un commit) :
   l'APK généré embarquera ces identifiants via `BuildConfig`.

Sans ces secrets, le build reste vert : l'écran "Se connecter à Deezer" affiche
simplement un message expliquant qu'il manque des identifiants, au lieu de planter.

Pour un build local (Android Studio), une alternative à `-P` est un fichier
`android/local.properties` (gitignoré, jamais commité) contenant :
```
deezerAppId=...
deezerAppSecret=...
```
Le `redirect_uri` par défaut (`https://vizuzik.local/oauth/callback`) n'a pas
besoin d'être un serveur réel : Vizuzik intercepte la navigation vers cette URL
directement dans la WebView de connexion pour en extraire le code d'autorisation.
Il est personnalisable via `-PdeezerRedirectUri=...` / `deezerRedirectUri=...` si
besoin, du moment qu'il correspond au domaine déclaré sur developers.deezer.com.

### Architecture

- `data/remote/deezer/` : `DeezerOAuthConfig` (URL d'autorisation), `DeezerHttp`
  (GET minimal, zéro dépendance ajoutée), `DeezerAuthRepository` (échange du code
  contre un jeton, persistance DataStore — Deezer ne fournit pas de refresh token),
  `DeezerApiClient` (albums/playlists via l'API publique), `DeezerPlaybackLauncher`
  (`playFromSearch` sur la session Deezer active).
- `ui/deezer/` : écran de connexion (WebView OAuth) et écran de bibliothèque
  (albums/playlists, avec action de lancement).
- `player/deezer/DeezerRemotePlayer` : implémente `MusicPlayer` en reflétant l'état
  de la session Deezer (`MediaController.Callback` + rafraîchissement périodique)
  et en lui relayant play/pause/next/prev/seek.
- `player/MusicPlayerRouter` : unique `MusicPlayer` injecté dans toute l'UI,
  délègue à `Media3MusicPlayer` (local) ou `DeezerRemotePlayer` selon
  `PlaybackSourceController.activeSource` — aucun écran existant n'a eu besoin
  d'être réécrit pour ça.
- `diagnostics/` : sonde MediaSession utilisée pendant le développement pour
  valider en direct ce que la session Deezer honore réellement (accessible depuis
  Réglages → *Sonde Deezer*, gardée pour du diagnostic futur si Deezer change son
  comportement).
