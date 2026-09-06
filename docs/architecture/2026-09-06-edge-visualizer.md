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
audio réel (`AudioPlaybackCapture` via `AudioCaptureService`, sans aucun micro) n'apparaît dans
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
déjà produites par `AudioCaptureService`), intensité, épaisseur, luminosité, sensibilité,
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

### Ce qui ne change pas

Permissions déjà en place (`RECORD_AUDIO`, `FOREGROUND_SERVICE`,
`FOREGROUND_SERVICE_MEDIA_PROJECTION`), le badge en trois temps (expliquer une fois, mémoriser, ne
plus jamais redemander) pour `SYSTEM_ALERT_WINDOW`, et la règle d'arrêt : `onTaskRemoved()` coupe
le service dès que l'utilisateur retire Vizuzik des applications récentes — même règle que
`AudioCaptureService`, c'est le vrai « stop », pas simplement Deezer au premier plan.

## Limitations

- Sous Android 7 et moins (`Build.VERSION.SDK_INT < 26`), `TYPE_APPLICATION_OVERLAY` n'existe pas :
  le badge et le bouton de réglages restent cachés plutôt que d'offrir une fonctionnalité qui
  échouerait silencieusement.
- Le consentement `MediaProjection` (audio réel) doit être redonné après un swipe complet de
  Vizuzik hors des applications récentes, comme aujourd'hui pour le visualiseur plein écran — ce
  n'est pas spécifique à l'overlay.
- **Non testé sur appareil réel dans cette session** : l'environnement de développement utilisé
  ici n'a ni SDK Android ni émulateur, donc ni compilation Gradle ni test manuel n'ont pu être
  faits ici. Le code reprend fidèlement ce qui avait déjà tourné sur un appareil (fenêtre, rendu,
  palette), moins exactement la partie jamais mise en cause dans le revert précédent (le micro),
  mais une validation sur un appareil réel — Z Fold inclus pour le pli/dépli — reste à faire avant
  de considérer la fonctionnalité prête.
