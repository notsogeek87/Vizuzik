# Edge Visualizer

**Pour qui :** utilisateurs qui veulent voir Vizuzik réagir à la musique même quand ils sont
repassés sur l'app de musique elle-même (Deezer, Spotify, YouTube Music, un lecteur local...).

## Le principe

Le visualiseur plein écran de Vizuzik n'existe que tant qu'on le regarde. Edge Visualizer ajoute
un second mode : un contour lumineux dessiné sur les quatre bords de l'écran, **par-dessus l'app
de musique elle-même**, façon MuViz Edge. Il s'allume tout seul dès qu'un morceau joue et que
Vizuzik n'est pas à l'écran, et s'éteint tout seul dans le cas contraire — **y compris si Vizuzik
n'a jamais été ouvert** depuis le dernier redémarrage du téléphone : une fois activé une première
fois, ouvrir directement Deezer et lancer un titre suffit.

Purement décoratif : aucun geste n'est jamais capté par le contour, tout atteint l'app en dessous
exactement comme s'il n'était pas là.

## Le parcours

1. **Badge « ▶ Activer Edge Visualizer »**, à côté du badge d'état audio, dans la barre du haut.
2. **Au premier appui :** un écran d'explication apparaît — ce que le contour affiche, la
   permission système qui suit (**Afficher par-dessus les autres applications**), et le fait que
   c'est purement décoratif. *« Plus tard »* referme sans rien demander au système.
3. **« Continuer » :** l'écran système « Afficher par-dessus les autres applications » s'ouvre,
   pour Vizuzik spécifiquement. C'est le seul moment où on le voit.
4. **Une fois accordé :** le contour s'allume et s'éteint tout seul selon la lecture et selon que
   Vizuzik est ou non au premier plan — plus rien à faire.
5. **En cas de refus :** le refus n'est jamais redemandé automatiquement ; le badge reste
   disponible si on change d'avis.

## Ce qui fait réagir le contour

Le contour réagit en direct à la musique dès que `RECORD_AUDIO` est accordé — via
`android.media.audiofx.Visualizer`, attaché à la session audio de l'app suivie, **sans jamais
ouvrir de fenêtre de consentement**. C'est la source unique de toute l'application, partagée avec
le lecteur plein écran (voir
[Une seule source audio](../architecture/2026-09-07-source-audio-unique.md)).

Il n'y a pas de repli : quand elle n'a rien à écouter (autorisation refusée, ou aucune session
audio à suivre), le contour passe en régime ambiant — respiration douce, plus un à-coup honnête sur
chaque changement de morceau ou lecture/pause — et n'invente jamais de rythme.

## Réglages

L'icône réglages (⚙) à côté du badge ouvre le panneau :

| Réglage | Effet |
|---|---|
| Style | Une seule implémentation pour l'instant (« Contour lumineux ») ; l'architecture permet d'en ajouter d'autres plus tard. |
| Fréquences utilisées | Quelle partie du spectre fait varier le contour : tout le spectre, seulement les basses, les médiums, ou les aigus. |
| Couleurs | Auto (les trois accents extraits de la pochette du morceau) ou trois couleurs fixes. |
| Intensité / Épaisseur / Luminosité / Sensibilité | Des multiplicateurs sur la réaction visuelle — 1 = comportement par défaut. |
| Haut / Bas / Gauche / Droite | Active ou désactive chaque bord indépendamment. |

Les changements s'appliquent immédiatement, même si le contour est déjà affiché — pas besoin de
le redémarrer.

## Prérequis et limitations

- Android 8 (API 26) ou supérieur — en dessous, `TYPE_APPLICATION_OVERLAY` n'existe pas et le
  badge reste masqué.
- La réaction en direct demande `RECORD_AUDIO` déjà accordé ; sans ça (et sans capture
  session audio à suivre), le contour respire en ambiant, jamais en inventant un tempo.
- Une fois activé une première fois (réglage + permission d'overlay accordée), fonctionne même si
  Vizuzik n'est plus jamais ouvert ensuite — voir
  [l'ADR correspondant](../architecture/2026-09-06-edge-visualizer.md) pour comment.
- N'ouvre jamais le microphone : une première version le faisait, mais ça s'est révélé peu fiable
  et a été abandonné (voir la
  même ADR).

## Repartir de zéro

L'activation est stockée dans `localStorage` sous la clé `vizuzik:edgeOverlay` (`"on"` /
`"off"`), et l'écran d'explication déjà vu sous `vizuzik:edgeOverlaySheetSeen` — mais aussi, en
miroir, côté natif (`EdgeOverlayPreference`), puisque c'est ce que lit `EdgeOverlayController`
pour démarrer le contour tout seul sans que la page n'ait jamais tourné. Les réglages du panneau
vivent côté natif (`EdgeConfig`, lu et écrit par `OverlayEdgeGlowService` et `DeezerMediaPlugin`),
puisque le service qui dessine le contour n'a pas accès au `localStorage` de la page.
