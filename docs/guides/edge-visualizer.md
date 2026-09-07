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
| Seulement par-dessus l'app de musique | Masque le contour dès que Deezer/Spotify n'est plus à l'écran. Activé par défaut ; demande l'autorisation « Accès aux données d'utilisation » — voir plus bas. |
| Style | « Barres » (32 bandes séparées, le défaut), « Contour lumineux » (une seule bordure) ou « Cocon » (un faisceau tressé autour de la pochette de Deezer — voir plus bas, c'est le seul des trois qui ne se limite pas aux bords). |
| Fréquences utilisées | Quelle partie du spectre fait varier le contour : tout le spectre, seulement les basses, les médiums, ou les aigus. |
| Couleurs | Auto (les trois accents extraits de la pochette du morceau) ou trois couleurs fixes. |
| Intensité / Épaisseur / Luminosité / Sensibilité | Des multiplicateurs sur la réaction visuelle — 1 = comportement par défaut. |
| Haut / Bas / Gauche / Droite | Active ou désactive chaque bord indépendamment. |

Les changements s'appliquent immédiatement, même si le contour est déjà affiché — pas besoin de
le redémarrer.

## Le style « Cocon »

Les styles « Barres » et « Contour lumineux » ne dessinent jamais que sur les quatre bords de
l'écran — voir *Le principe* plus haut : la superposition est censée encadrer l'app suivie, pas
la recouvrir. « Cocon » déroge à cette règle : un faisceau d'une vingtaine de brins fins,
dessiné en carré arrondi (une superellipse, pas un cercle : ce qu'il encadre est une pochette
carrée) tout autour de la pochette de l'app suivie plutôt que sur les bords. Même idée que le
mode `cocoon` du lecteur plein écran — voir
[Les sept modes de visualisation](modes-de-visualisation.md).

Tout le reste vient du fait que ce style dessine **par-dessus une autre app**, et pas sur l'écran
noir de Vizuzik. Deezer teinte sa page de lecture d'après la pochette — donc le fond est souvent
clair, et surtout il est de la même couleur que la palette, puisque les deux sortent de la même
image. Trois choses en découlent :

- **l'opacité compte plus que la couleur.** Le dégradé qui habille les brins garde l'essentiel du
  ruban autour d'un cinquième de l'opacité pleine, et deux arcs fins seulement brûlent en
  blanc. Une lumière se lit comme une lumière quand elle est concentrée ; maintenir toute la
  bande à une valeur moyenne, c'est ce qui donnait un brouillard laiteux ;
- **les couleurs sont poussées loin du gris** (et les crêtes vers le blanc), pour un contraste qui
  ne dépend pas d'avoir une teinte différente du fond — ce qui est impossible ici ;
- **chaque brin est posé sur une copie plus sombre de lui-même**, la même raison qui fait qu'un
  texte clair porte une ombre, et quelques brins sont repassés en traits larges et très
  transparents pour le halo : la lumière déborde, un trait fin tout seul se lit comme un fil.

La toute première version utilisait au contraire une fusion additive, sans rien de tout ça : sur
une page vert clair, éclaircir un fond déjà clair ne produit quasiment rien.

Le problème de fond, c'est que cette superposition n'a aucun moyen de lire la position réelle de
la pochette dans l'app suivie — pas d'accès à sa hiérarchie de vues, aucun service
d'accessibilité branché pour ça. `EdgeGlowView` estime donc cette position par des fractions
fixes de la taille de l'écran, mesurées sur des captures de Deezer.

Deezer ayant **deux mises en page**, il y a deux jeux de fractions, et la forme de la fenêtre
suffit à choisir laquelle s'applique — sans rien demander à Deezer. Les deux ont été mesurées sur
un Z Fold :

| Écran | Mise en page Deezer | Pochette |
|---|---|---|
| Plié / portrait (1248×1823) | une colonne | carré de 0,583 de la largeur, centré, bord haut à 0,105 de la hauteur |
| Déplié / paysage (2448×1575) | deux volets | dans le volet gauche : centré verticalement, centré sur le premier quart de la largeur, dimensionné sur la **hauteur** (0,619) puisque c'est elle qui contraint une mise en page large |

Comme la largeur et la hauteur sont relues à chaque image, plier ou déplier le téléphone
déplace le ruban avec la pochette, sans que quoi que ce soit ait à en être prévenu. Sur la mise
en page dépliée, la pochette est très près du bord gauche : le faisceau est alors resserré pour
tenir dans la place disponible plutôt que de sortir de l'écran.

Un écran ou une version de Deezer éloignés de ces deux références dérivent, et rien ici ne peut
le corriger sans véritable inspection de la mise en page — sans parler de Spotify ou d'un autre
lecteur suivi, dont les mises en page sont différentes.

## Seulement par-dessus l'app de musique

Par défaut, la superposition ne s'affiche que lorsque l'app suivie est **réellement à l'écran** :
sortir de Deezer pour lire un message la fait disparaître, y revenir la ramène. C'est le premier
réglage du panneau.

Une fenêtre de superposition ne voit pas ce qu'il y a en dessous, et la session multimédia ne dit
rien de l'app affichée — un Deezer en pause en arrière-plan y ressemble trait pour trait à un
Deezer au premier plan. La seule façon de le savoir sans service d'accessibilité est
`UsageStatsManager`, qui demande l'autorisation spéciale **« Accès aux données d'utilisation »**
(voir `ForegroundApp.java`). Elle est accordée dans un écran système, comme les deux autres
autorisations spéciales de l'app, et n'est demandée qu'au moment où on active ce réglage.

Tant qu'elle n'est pas accordée, rien ne peut répondre à la question, et le contour **reste
visible partout** plutôt que de se cacher au jugé : une décoration qui refuse silencieusement
d'apparaître est un bien pire échec qu'une décoration qui apparaît de trop. Le libellé du réglage
le dit dans ce cas. Rien d'autre n'est lu de ces statistiques : seulement le nom du dernier
paquet passé au premier plan, jamais conservé ni envoyé nulle part.

## Prérequis et limitations

- Android 8 (API 26) ou supérieur — en dessous, `TYPE_APPLICATION_OVERLAY` n'existe pas et le
  badge reste masqué.
- « Seulement par-dessus l'app de musique » demande en plus l'accès aux données d'utilisation ;
  sans lui, le réglage n'a simplement aucun effet.
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
