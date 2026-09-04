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
