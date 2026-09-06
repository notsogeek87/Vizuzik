# Edge Visualizer

**Pour qui :** utilisateurs qui veulent voir Vizuzik réagir à la musique même quand ils sont
repassés sur l'app de musique elle-même (Deezer, Spotify, YouTube Music, un lecteur local...).

## Le principe

Le visualiseur plein écran de Vizuzik n'existe que tant qu'on le regarde. Edge Visualizer ajoute
un second mode : un contour lumineux dessiné sur les quatre bords de l'écran, **par-dessus l'app
de musique elle-même**, façon MuViz Edge. Il s'allume tout seul dès qu'un morceau joue et que
Vizuzik n'est pas à l'écran, et s'éteint tout seul dans le cas contraire.

Purement décoratif : aucun geste n'est jamais capté par le contour, tout atteint l'app en dessous
exactement comme s'il n'était pas là.

## Le parcours

1. **Badge « ▶ Activer Edge Visualizer »**, à côté de celui du son réel, dans la barre du haut.
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

Comme le visualiseur plein écran, le contour ne réagit à un vrai rythme que si le son réel est
capté (voir [Activer le son réel](capture-audio.md)) — la même capture, réutilisée telle quelle
pour l'overlay. **Le mode Micro ne fait pas réagir le contour** : il retombe en régime ambiant
(respiration douce + un à-coup honnête sur chaque changement de morceau ou lecture/pause), le
temps que le son réel soit activé.

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
- Le contour ne réagit à un vrai rythme que si le son réel est actif (voir ci-dessus) ; sans ça,
  il respire en ambiant, jamais en inventant un tempo.
- S'arrête pour de bon quand Vizuzik est retiré des applications récentes — même règle que la
  capture audio elle-même.
- Ne capture jamais le microphone en arrière-plan : une première version le faisait pour que le
  contour réagisse même en mode Micro, mais ça s'est révélé peu fiable (voir
  [l'ADR correspondant](../architecture/2026-09-06-edge-visualizer.md)) et a été abandonné.

## Repartir de zéro

L'activation est stockée dans `localStorage` sous la clé `vizuzik:edgeOverlay` (`"on"` /
`"off"`), et l'écran d'explication déjà vu sous `vizuzik:edgeOverlaySheetSeen`. Les réglages du
panneau vivent côté natif (`EdgeConfig`, lu et écrit par `OverlayEdgeGlowService` et
`DeezerMediaPlugin`), puisque le service qui dessine le contour n'a pas accès au `localStorage`
de la page.
