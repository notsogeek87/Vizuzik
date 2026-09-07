# Edge Visualizer : le contour lumineux, sans micro cette fois

**Statut :** adopté · **Date :** 2026-09-06 · **Concerne :**
`src/main.js`, `index.html`, `src/style.css`, `DeezerMediaPlugin.java`, `DeezerMediaBridge.java`,
`AudioLevelsBridge.java`, `EdgeConfig.java`, `OverlayEdgeGlowService.java`, `EdgeGlowView.java`,
`OverlayPalette.java`, `AndroidManifest.xml`

## Ce n'est pas la première tentative

Cette fonctionnalité — un contour lumineux dessiné par-dessus l'app de musique elle-même — a déjà
été construite et retirée une fois sur ce dépôt (voir le commit *« Retirer le contour lumineux
par-dessus Deezer »* et la doc qu'il a supprimée,
`2026-09-04-contour-lumineux-par-dessus-deezer.md`, récupérable dans l'historique git). Le verdict
de l'époque : « elle n'a jamais fonctionné de façon fiable sur l'appareil de test, et la faire
tenir demandait d'empiler des contournements (accès micro en arrière-plan, type de service de
premier plan, propriété partagée du micro entre deux composants) dont le coût dépassait largement
l'effet visuel obtenu ».

En reprenant les commits intermédiaires de cette première tentative, les quatre bugs réels
rencontrés étaient **tous** consécutifs à une seule décision : faire tourner un second
`AudioRecord` sur le microphone, à l'intérieur du service d'arrière-plan, pour que le contour
réagisse même quand l'utilisateur avait choisi « Micro » comme source dans le plein écran.

1. Un vsync qui ne se redéclenchait jamais pour une fenêtre d'overlay posée par un `Service`
   (`Choreographer` → `Handler.postDelayed`, un bug du mécanisme de rendu lui-même, indépendant du
   micro — corrigé et conservé ici).
2. Une `SecurityException` fatale (tue toute l'app) en déclarant
   `FOREGROUND_SERVICE_TYPE_MICROPHONE` sans `RECORD_AUDIO` déjà accordé.
3. Deux `AudioRecord` se disputant le même micro : le plein écran le relâche pile au moment où le
   service d'overlay l'ouvre (et l'inverse au retour), et celui qui perd la course échoue en
   silence.
4. Un état « live » de `EdgeGlowView` jamais remis à faux pour la source micro, qui laissait le
   contour figé sur la dernière valeur reçue dès qu'une capture mourait sans bruit.

Le mécanisme d'overlay en lui-même — la fenêtre `WindowManager`, `EdgeGlowView`, `OverlayPalette`
— n'a jamais été mis en cause une fois son propre bug de vsync corrigé (point 1). Et le chemin
audio réel (sans aucun micro) n'apparaît dans
aucun des bugs listés.

## La décision

**Reprendre l'architecture précédente presque telle quelle, mais sans jamais ouvrir de micro dans
le service d'arrière-plan.** `OverlayEdgeGlowService` ne réagit en direct qu'au pipeline
`AudioPlaybackCapture` déjà existant (le même `AudioLevelsBridge` qui alimente le visualiseur
plein écran) — celui qui capte la sortie audio de l'app suivie (Deezer, Spotify, YouTube Music,
un lecteur local), indépendamment du micro du téléphone. Quand la source choisie dans le plein
écran est « Micro », ou qu'aucune capture ne tourne, le contour retombe simplement en régime
ambiant (respiration + pulse sur changement de piste/lecture-pause) — exactement comme le
visualiseur plein écran avant que le son réel ne soit accordé. Il ne tente jamais de rouvrir le
micro de sa propre initiative.

Ça élimine les quatre bugs listés ci-dessus sans rien perdre par rapport au scénario visé : ouvrir
Deezer/Spotify/YouTube Music/un lecteur local, lancer un titre, voir le contour réagir en
arrière-plan — ce scénario ne suppose jamais le micro.

### Deux écouteurs, pas un seul (repris à l'identique)

`DeezerMediaBridge` et `AudioLevelsBridge` repassent d'un `Listener` unique à un
`CopyOnWriteArraySet<Listener>` : `OverlayEdgeGlowService` s'abonne en plus du pont web
(`DeezerMediaPlugin`), jamais à sa place — les deux tournent potentiellement en même temps
(l'overlay pendant que Vizuzik est en arrière-plan, jusqu'au retour).

### Le rendu (repris à l'identique) : glow ambient/live, palette de la pochette

`EdgeGlowView` applique la même distinction que `src/visualizer.js` (voir
[Le rythme hors capture](2026-09-03-rythme-hors-capture.md)) : avec de l'audio réellement capté,
l'épaisseur et une pulsation de battement suivent la basse ; sans capture, le contour respire sur
trois oscillateurs lents sans période commune, sans jamais inventer de rythme. La couleur voyage
en continu entre les trois accents extraits de la pochette (`OverlayPalette`, portage natif de
`src/palette.js`, puisque le service tourne hors de la webview) — ou entre des couleurs fixes
choisies dans les réglages, voir plus bas.

### Les réglages (nouveau) : `EdgeConfig`

Contrairement à la première tentative (qui n'avait qu'un bouton on/off), cette version ajoute un
panneau de réglages complet : style (un seul pour l'instant, `glow`), fréquences utilisées
(spectre entier / basses / médiums / aigus — un découpage approximatif des 32 bandes 55 Hz-7000 Hz
déjà produites par la capture native), intensité, épaisseur, luminosité, sensibilité,
couleurs (auto depuis la pochette, ou trois couleurs fixes), et l'activation indépendante de
chacun des quatre bords.

Ces réglages vivent dans `EdgeConfig` (`SharedPreferences`, même fichier que
`MusicAppPreference`) plutôt que dans le `localStorage` de la webview : `OverlayEdgeGlowService`
n'y a pas accès. `DeezerMediaPlugin.setEdgeConfig()` écrit à chaque changement dans le panneau ;
le service écoute les changements (`SharedPreferences.OnSharedPreferenceChangeListener`) et les
applique en direct à `EdgeGlowView` — pas besoin de redémarrer le service pour qu'un curseur
déplacé prenne effet immédiatement.

L'architecture (un `style` nommé dans `EdgeConfig`, une seule implémentation `glow` dans
`EdgeGlowView.drawGlow()`) laisse la place à d'autres styles plus tard (particules, vagues,
pulsations) sans toucher au reste du pipeline — chacun serait une autre branche de rendu lisant
la même config et les mêmes niveaux audio.

### Écrans pliables (nouveau)

`OverlayEdgeGlowService` déclare `onConfigurationChanged()` : un pli/dépli (ou toute autre
bascule qui redimensionne l'écran par défaut) fait recalculer la fenêtre via
`WindowManager.updateViewLayout()`. En pratique, `MATCH_PARENT` + `FLAG_LAYOUT_NO_LIMITS` suivent
déjà la taille d'écran tout seuls et `EdgeGlowView` relit `getWidth()`/`getHeight()` à chaque
frame — ce recalcul explicite évite simplement un instant visible aux anciennes dimensions le
temps que le système redéclenche un layout de son côté. Non vérifié sur un appareil pliable réel
(voir la section Tests).

### Mise à jour : plus besoin de MediaProjection pour le contour (`TrackedSessionAudioSource`)

Après publication de cette ADR, une question est restée ouverte : le mode « Micro » du plein
écran ne capte que le microphone physique du téléphone (`MicCaptureThread`, `AudioSource.MIC`) —
aucun lien avec le flux numérique de l'app suivie, contrairement à ce qu'on pourrait croire. Testé
et confirmé sur appareil (Samsung Galaxy Z Fold8, via un prototype de diagnostic isolé,
`VisualizerProbe.java`, conservé dans l'historique git) :

- `android.media.audiofx.Visualizer` attaché à la session 0 (« output mix ») échoue net sur cet
  appareil (`RuntimeException: Cannot initialize Visualizer engine, error: -3`) — capture globale
  refusée à une app tierce, comme attendu sur un Android récent.
- `AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION` — la diffusion que les piles de lecture
  standard (`MediaPlayer`, `ExoPlayer`) envoient à l'ouverture d'une session audio non nulle, avec
  le package et le `audioSessionId` — est bien envoyée par Deezer. Un `Visualizer` attaché à cette
  session précise capte du signal réel et variable (FFT magnitude observée entre 0 et ~2990 sur
  une lecture réelle), avec seulement `RECORD_AUDIO`, sans jamais afficher la fenêtre
  MediaProjection.

`TrackedSessionAudioSource.java` porte ce mécanisme en production : il écoute cette diffusion,
attache un `Visualizer` à la session de l'app suivie, et transforme la capture FFT en le même
spectre 32 bandes log (55 Hz-7000 Hz) —
`EdgeGlowView.pushLevels()` ne voit aucune différence entre les sources. Tout le travail
(construction du `Visualizer`, callbacks de capture, libération) tourne sur un `HandlerThread`
dédié : un objet `AudioEffect` livre ses callbacks sur le thread qui l'a construit s'il a un
Looper, donc le construire depuis le thread principal (celui du `BroadcastReceiver` par défaut)
aurait fait tourner jusqu'à 30 conversions FFT→bandes par seconde sur le même thread que le rendu
de la fenêtre.

`OverlayEdgeGlowService` garde `AudioLevelsBridge` (MediaProjection) branché en parallèle, comme
repli : si la diffusion n'arrive jamais (autre app, autre version d'Android) ou si `RECORD_AUDIO`
n'est pas encore accordé, le contour continue de fonctionner exactement comme avant. Les deux
sources peuvent en théorie pousser des niveaux en même temps ; `EdgeGlowView.pushLevels()` est
donc devenu `synchronized` (il touche un buffer circulaire non protégé, jusque-là jamais appelé
que par un seul producteur à la fois).

L'échelle qui transforme une magnitude FFT brute en niveau 0-1 (`MAGNITUDE_SCALE`/
`MAGNITUDE_CEILING` dans `TrackedSessionAudioSource`) est une première approximation : Visualizer
ne fournit aucune référence absolue à laquelle se calibrer sans regarder le contour réagir
réellement sur un appareil — à ajuster selon le retour visuel. Premier essai vu comme « pas assez
flagrant » sur l'appareil de test : `MAGNITUDE_CEILING` abaissé de 600 à 90 (sensibilité) et
l'épaisseur/luminosité de base d'`EdgeGlowView` sensiblement relevées (voir le commit qui suit
cette ADR) — le contour est désormais pensé pour se voir d'un coup d'œil à travers la pièce,
par-dessus n'importe quelle app, pas comme un simple liseré discret.

`VisualizerProbe.java`, le prototype de diagnostic qui a servi à valider tout ça (panneau « 🔬 Test
Visualizer », `getVisualizerProbeStatus()`), a été retiré une fois la question tranchée —
récupérable dans l'historique git si une nouvelle question du même genre se pose plus tard.

### Démarrage sans jamais ouvrir Vizuzik (`EdgeOverlayController`)

Jusqu'ici, toute la décision « faut-il faire tourner `OverlayEdgeGlowService` ? » vivait côté web
(`syncEdgeOverlay()` dans `main.js`) — ce qui suppose que la webview tourne. Si Vizuzik n'a jamais
été ouvert depuis le dernier redémarrage du téléphone (l'app suivie jouant déjà, ou l'utilisateur
lançant directement Deezer), rien n'existait côté natif pour prendre cette décision à sa place.

`EdgeOverlayController` (singleton) porte la même décision en natif : activé (`EdgeOverlayPreference`,
miroir du réglage web), permission d'overlay accordée (`Settings.canDrawOverlays()`), un titre
joue réellement (`DeezerMediaBridge`), et Vizuzik lui-même pas au premier plan
(`MainActivity.isForeground()`, un simple drapeau statique posé dans `onResume()`/`onPause()` —
faux par défaut, ce qui est exactement juste quand le processus de l'app n'a été relancé que pour
héberger `NowPlayingListenerService`, sans que `MainActivity` n'ait jamais tourné). Enregistré
comme écouteur de `DeezerMediaBridge` depuis `NowPlayingListenerService.onListenerConnected()` —
le seul composant garanti vivant dès que l'accès aux notifications est accordé, indépendamment de
`MainActivity`.

Les deux orchestrateurs (web et natif) passent tous les deux par
`OverlayEdgeGlowService.requestStart()`/`requestStop()`, jamais directement par
`startService()`/`stopService()` : ils ne peuvent donc jamais se contredire sur *comment* démarrer
ou arrêter, seulement décider *quand* — et comme ils calculent la même réponse à partir des mêmes
signaux, un appel redondant de l'un des deux est simplement un no-op (déjà idempotent des deux
côtés).

### Ce qui ne change pas

Permissions déjà en place (`RECORD_AUDIO`, `FOREGROUND_SERVICE`,
`FOREGROUND_SERVICE_MEDIA_PROJECTION`), le badge en trois temps (expliquer une fois, mémoriser, ne
plus jamais redemander) pour `SYSTEM_ALERT_WINDOW`, et la règle d'arrêt : `onTaskRemoved()` coupe
le service dès que l'utilisateur retire Vizuzik des applications récentes.

> **Révisé le 2026-09-07.** Cette règle d'arrêt a été supprimée : elle contredisait le démarrage
> autonome (l'overlay tourne précisément quand Vizuzik n'est pas là) et, surtout, elle ne
> fonctionnait pas — `NowPlayingListenerService` survit au retrait de la tâche et continue de
> publier, donc le premier événement de lecture suivant relançait l'overlay dans la seconde. Ce
> qui l'éteint est le réglage « Mode superposition », ou l'arrêt de la lecture.

## Limitations

- Sous Android 7 et moins (`Build.VERSION.SDK_INT < 26`), `TYPE_APPLICATION_OVERLAY` n'existe pas :
  le badge et le bouton de réglages restent cachés plutôt que d'offrir une fonctionnalité qui
  échouerait silencieusement.
- Si le processus démarre alors que la lecture est **déjà** en cours, la diffusion annonçant la
  session audio est passée et aucune API ne permet de la redemander : l'effet reste ambiant
  jusqu'au morceau suivant. Voir
  [Une seule source audio](2026-09-07-source-audio-unique.md).
- **Compilation vérifiée par CI, pas testée manuellement dans cette session** : l'environnement de
  développement utilisé ici n'a ni SDK Android ni émulateur — impossible de compiler ou de lancer
  l'app localement. Chaque changement a donc été vérifié via le workflow GitHub Actions
  (`android.yml`, `assembleDebug`) avant d'être propagé.
- Le mécanisme `TrackedSessionAudioSource` (Visualizer + diffusion système) a été validé
  expérimentalement sur le Z Fold8 via `VisualizerProbe` (voir la section « Mise à jour »
  ci-dessus) — capture réelle et variable confirmée. **Le rendu du contour lui-même une fois
  branché dessus (constante d'échelle, latence perçue, comportement en arrière-plan prolongé,
  pli/dépli) reste à confirmer visuellement sur l'appareil.**

## Mise à jour 2026-09-07 : la source est devenue celle de toute l'app

`TrackedSessionAudioSource`, introduit ici pour l'overlay, a remplacé depuis les trois sources
sélectionnables du lecteur plein écran (micro, MediaProjection, ambiance) : c'est désormais la
seule de l'application, partagée par les deux effets. Les mentions de repli sur `MediaProjection`
ci-dessus ne valent plus. Voir
[Une seule source audio](2026-09-07-source-audio-unique.md).
