# Un contour lumineux par-dessus Deezer, façon MuViz Edge

**Statut :** adopté · **Date :** 2026-09-04 · **Concerne :**
`src/main.js`, `index.html`, `src/style.css`, `DeezerMediaPlugin.java`, `DeezerMediaBridge.java`,
`AudioLevelsBridge.java`, `OverlayEdgeGlowService.java`, `EdgeGlowView.java`,
`OverlayPalette.java`, `AndroidManifest.xml`

## Le problème

Vizuzik est un écran plein écran qu'il faut regarder pour voir les visuels : dès qu'on revient à
Deezer lui-même (pour chercher un titre, lire les paroles, gérer une playlist), l'écran réactif
disparaît. Des applications comme [MuViz
Edge](https://play.google.com/store/apps/details?id=com.sparkine.muvizedge) résolvent ça
autrement : un effet visuel dessiné **par-dessus n'importe quelle app**, sans jamais avoir à la
quitter.

## Ce qui n'était pas retenu

Un calque plein écran façon MuViz Edge (tout l'écran recouvert d'un effet semi-transparent) a été
écarté : ça rendrait Deezer illisible en dessous — texte, listes, pochettes — alors que le but est
d'utiliser Deezer normalement. Un overlay interactif (tap sur le bord pour passer au titre
suivant) a aussi été écarté : capter le moindre geste près des bords entre en conflit avec les
gestes système (retour, barre de notifications) et avec Deezer lui-même.

## La décision

**Un contour lumineux sur les quatre bords de l'écran, purement décoratif, allumé automatiquement
dès que Deezer (ou Spotify) joue au premier plan.** L'écran plein écran existant ne change pas :
c'est un second mode, pas un remplacement.

### Une fenêtre système, pas un second Vizuzik

`OverlayEdgeGlowService` ajoute une vue (`EdgeGlowView`) via `WindowManager`, en
`TYPE_APPLICATION_OVERLAY`. Deux réglages la rendent strictement décorative :

- `FLAG_NOT_TOUCHABLE` — aucun geste n'est jamais intercepté ; tout atteint Deezer en dessous
  exactement comme si la fenêtre n'existait pas.
- `FLAG_NOT_FOCUSABLE` — jamais de focus clavier ou de D-pad volé.

Cette fenêtre demande une permission spéciale, distincte de tout ce que Vizuzik demandait jusque
là : **« Afficher par-dessus les autres applications »** (`SYSTEM_ALERT_WINDOW`,
`Settings.ACTION_MANAGE_OVERLAY_PERMISSION`). Comme pour la capture audio (voir [Le consentement
de capture audio](2026-09-03-consentement-capture-audio.md)), un écran interne explique une seule
fois ce que c'est et pourquoi, avant que la fenêtre système n'apparaisse — cette permission a une
réputation chargée (overlays publicitaires), le premier réflexe sans explication serait de la
refuser.

### Qui décide de l'allumer : le web, jamais le service lui-même

`OverlayEdgeGlowService` n'a aucune opinion sur le moment où il doit tourner — il se contente de
dessiner tant qu'il est vivant. La décision reste entièrement dans `syncEdgeOverlay()`
(`src/main.js`), appelée après chaque évènement qui pourrait la changer : lecture/pause, morceau
qui disparaît, retour ou départ de Vizuzik au premier plan, permission accordée, réglage basculé.
Elle s'allume quand **tout** est vrai à la fois :

- le réglage est activé (`vizuzik:edgeOverlay`) ;
- la permission système est accordée ;
- un morceau joue réellement ;
- **Vizuzik lui-même n'est pas au premier plan** (`document.visibilityState !== "visible"`).

Cette dernière condition est le cœur de la fonctionnalité : le contour n'a de raison d'exister que
lorsque l'écran plein écran de Vizuzik n'est déjà pas là pour montrer les mêmes informations.
Aucune détection de « quelle app est au premier plan » n'a été nécessaire — ni `UsageStatsManager`,
ni service d'accessibilité (écarté de toute façon, voir la même ADR citée plus haut, pour des
raisons de règles Play Store) : Vizuzik connaît déjà sa propre visibilité, et c'est suffisant.

### Deux écouteurs, pas un seul

`DeezerMediaBridge` et `AudioLevelsBridge` n'admettaient jusqu'ici qu'un seul écouteur
(`setListener`), tenu par `DeezerMediaPlugin`. `OverlayEdgeGlowService` a besoin des mêmes
informations (pochette/titre pour la palette, niveaux audio pour la réactivité) **en plus** du
pont web, pas à sa place — les deux tournent potentiellement en même temps (l'overlay pendant que
Vizuzik est en arrière-plan, jusqu'à ce qu'on y revienne). Les deux singletons sont donc passés
d'un champ `Listener` unique à un `CopyOnWriteArraySet<Listener>` (`addListener`/`removeListener`).

### Le rendu : mêmes règles que le moteur plein écran, portées à la main

`EdgeGlowView` applique exactement la distinction établie dans [Le rythme hors
capture](2026-09-03-rythme-hors-capture.md) : avec de l'audio réellement capté
(`AudioLevelsBridge`), l'épaisseur et une pulsation de battement suivent la basse (même détection
adaptative — seuil à 1,32× la moyenne glissante, 190 ms d'écart minimum) ; sans capture, le
contour respire sur trois oscillateurs lents sans période commune, sans jamais inventer de rythme.
Seule la couleur voyage en continu dans les deux régimes, comme dans le moteur plein écran.

`OverlayPalette.java` est un portage à la main de `src/palette.js` (même découpage par teinte,
même score, même règle des 32° d'écart minimum entre accents) : `OverlayEdgeGlowService` tourne
hors de la webview, sans accès au canvas qui fait ce travail côté plein écran. **Cette duplication
est assumée** plutôt que contournée par un pont supplémentaire : porter fidèlement un algorithme
pur (pas d'état, pas d'I/O) est plus simple à garder synchronisé qu'un aller-retour natif ↔ web à
chaque changement de morceau, alors même que le service peut tourner sans que la webview soit au
premier plan.

### Le service s'arrête avec les autres, pas avant

Même règle que `AudioCaptureService` : `onTaskRemoved()` arrête le service dès que l'utilisateur
retire Vizuzik des applications récentes — c'est le vrai moment d'arrêt, pas le simple fait que
Deezer soit au premier plan. Le type de service de premier plan déclaré est `specialUse` : aucun
des types spécifiques d'Android (`mediaPlayback`, `mediaProjection`…) ne correspond à un service
qui ne joue ni ne capture rien lui-même, seulement un effet visuel posé sur une autre app.

### Un régime ambiant taillé pour un coup d'œil, pas pour être regardé en continu

Les premiers essais (sans capture audio réelle activée) montraient un contour perçu comme figé :
il respirait sur les mêmes périodes lentes (30-82 s) que l'écran plein écran — pensées pour
quelqu'un qui regarde l'écran en continu, pas pour un contour qu'on aperçoit en passant sur
Deezer. Corrigé sur deux plans, toujours sans inventer de rythme :

- Périodes ramenées à 7/11/17 s et amplitude doublée (0,5 ± 0,4 au lieu de 0,4 ± 0,15) : le
  régime ambiant reste un mélange de trois oscillateurs sans période commune, juste assez rapide
  pour se voir respirer en quelques secondes.
- `EdgeGlowView.pulse()` : une vraie impulsion (changement de morceau, lecture/pause) déclenche
  désormais un à-coup visible sur le contour, comme `visualizer.pulse()` le fait déjà côté plein
  écran — l'overlay n'avait jusque-là aucune réaction à ces évènements pourtant réels. Le
  ralentissement de la décroissance (`PULSE_DECAY_MS`, environ 1,5 s au lieu d'une fraction de
  seconde) le rend visible même sur un simple coup d'œil, puisque contrairement au plein écran
  le contour est seul à porter cette impulsion.

### Le contour reprend le micro que le plein écran vient de lâcher

`showScreen()` et le `visibilitychange` du plein écran coupent le micro dès que Vizuzik quitte
l'écran lecteur ou passe en arrière-plan (rien à lui montrer sur un autre écran) — exactement le
moment où l'overlay existe. Résultat : en mode « Micro », le contour n'avait jamais de son réel
une fois sur Deezer, alors que l'utilisateur avait déjà donné son accord à l'écoute du micro côté
plein écran.

`AudioSourcePreference` (même schéma que `MusicAppPreference`) mémorise ce choix ("mic" / "real" /
"off") côté natif — `DeezerMediaPlugin.setAudioSourcePreference()` l'écrit à chaque changement et
au lancement. `OverlayEdgeGlowService` le lit à son propre démarrage : si c'est "mic" et que
`RECORD_AUDIO` est déjà accordé, il fait tourner son propre `MicCaptureThread` (la même classe que
`DeezerMediaPlugin` utilise, réutilisée telle quelle) pendant toute sa durée de vie, alimentant
`EdgeGlowView` directement. Jamais l'inverse : si "real" ou "off" est le choix mémorisé, l'overlay
n'ouvre jamais le micro de sa propre initiative — le consentement du plein écran ne s'étend qu'au
choix qu'il représente, pas au-delà. Un service en arrière-plan ne pouvant pas afficher de demande
de permission, l'absence de `RECORD_AUDIO` déjà accordé fait simplement retomber sur le régime
ambiant plutôt que d'échouer.

### Vizuzik n'ouvre plus Deezer/Spotify tout seul

[Choisir puis lancer l'app de musique soi-même](2026-09-03-lancement-automatique-deezer.md)
faisait ouvrir l'app suivie par Vizuzik lui-même au tout premier écran, quand aucune session
n'existait encore. Retiré à la demande : Vizuzik reste un compagnon d'affichage, jamais quelque
chose qui bascule vers une autre app de sa propre initiative. L'écran « Aucune lecture en cours »
attend simplement qu'un titre démarre côté Deezer ou Spotify. La reprise d'une session déjà
chargée mais en pause (`DeezerMedia.play()`) reste inchangée : elle ne bascule jamais vers une
autre app, elle envoie juste une commande de transport à une session qui existe déjà.

## Conséquences

- Le badge « Effets sur Deezer » (topbar, à côté de celui du son réel) suit le même parcours en
  trois temps que la capture audio : expliquer une fois, mémoriser, ne plus jamais redemander.
- Sur Android 7 et moins (`Build.VERSION.SDK_INT < 26`), le badge reste caché : le type de fenêtre
  utilisé n'existe pas, il n'y a rien à proposer plutôt qu'une fonctionnalité qui échouerait
  silencieusement.
- Refuser la permission n'est jamais redemandé automatiquement — un refus est une réponse, pas un
  échec (même principe que `vizuzik:realAudio` côté capture).
- Ajouter une autre app suivie n'a rien à changer ici : le service lit `DeezerMediaBridge` comme
  n'importe quel autre consommateur, sans savoir laquelle des deux apps connues est active.
