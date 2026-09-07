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
| Masquer hors de l'app de musique | Masque **tout** dès que Deezer/Spotify n'est plus à l'écran, au lieu de basculer sur le style de repli. Désactivé par défaut ; demande l'autorisation « Accès aux données d'utilisation » — voir plus bas. |
| Style | « Barres » (32 bandes séparées, le défaut), « Contour lumineux » (une seule bordure) ou « Cocon » (un faisceau tressé autour de la pochette de Deezer — voir plus bas, c'est le seul des trois qui ne se limite pas aux bords). |
| Hauteur des barres | Jusqu'où les barres montent. N'agit que sur le style « Barres » : le contour est une bordure d'épaisseur fixe, et le cocon se dimensionne sur la pochette. |
| Hors de l'app de musique | Ce que « Cocon » affiche quand Deezer n'est pas à l'écran : les barres ou le contour. Sans effet sur les deux autres styles. |
| Fréquences utilisées | Quelle partie du spectre fait varier le contour : tout le spectre, seulement les basses, les médiums, ou les aigus. |
| Couleurs | Auto (les trois accents extraits de la pochette du morceau) ou trois couleurs fixes. |
| Intensité / Épaisseur / Luminosité / Sensibilité | Des multiplicateurs sur la réaction visuelle — 1 = comportement par défaut. |
| Haut / Bas / Gauche / Droite | Active ou désactive chaque bord indépendamment. **Seul le bord haut est actif à l'installation** : le spectre sur les quatre bords à la fois est beaucoup pour un premier contact, et le haut est celui qui se lit comme appartenant au téléphone plutôt qu'à l'app affichée. Une installation existante garde ses bords. |

Les changements s'appliquent immédiatement, même si le contour est déjà affiché — pas besoin de
le redémarrer.

## Le style « Barres »

Les 32 bandes du spectre, chacune sur son bord. Ce n'est plus l'affichage brut des débuts :

- les niveaux sont **lissés de façon asymétrique** — montée immédiate sur une attaque, descente
  lente. Deux images consécutives d'un vrai spectre sautent beaucoup ; dessinées telles quelles,
  les barres tremblent au lieu de danser ;
- une **crête flottante** au-dessus de chaque barre retombe sous son propre poids, ce qui montre
  la force d'un coup après que la barre elle-même soit redescendue ;
- la rangée entière est peinte à travers un **dégradé qui court le long du bord** et balaye les
  trois accents de la pochette, au lieu de 32 bâtons de la même couleur ;
- les barres ont des **extrémités arrondies**, et leur hauteur maximale se mesure sur la **plus
  petite dimension de l'écran** : sur un écran large, une fraction de la largeur laisserait les
  rangées gauche et droite traverser presque un tiers de l'écran chacune.

## Le style « Cocon »

Les styles « Barres » et « Contour lumineux » ne dessinent jamais que sur les quatre bords de
l'écran — voir *Le principe* plus haut : la superposition est censée encadrer l'app suivie, pas
la recouvrir. « Cocon » déroge à cette règle : un faisceau d'une vingtaine de brins fins,
dessiné en carré arrondi (une superellipse, pas un cercle : ce qu'il encadre est une pochette
carrée — l'exposant vaut 8, ce qui place le coin à 72 % du chemin entre le cercle et le carré et
colle à l'arrondi de la pochette ; à 3,4, il n'en faisait que 37 % et se lisait comme une bulle) tout autour de la pochette de l'app suivie plutôt que sur les bords. Même idée que le
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
- **la teinte est tournée vers la complémentaire de la pochette** (150°), et poussée loin du
  gris. Sans ça, le ruban tombe sur la couleur même de la page — sur une pochette verte, du vert
  sur du vert, qu'aucune saturation ne rattrape. C'est toujours la couleur du morceau, mais
  répondue au lieu d'être répétée, et elle change à chaque titre. Le réglage
  « Couleurs / Personnalisées » court-circuite tout ça ;
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

### Seulement là où il a un sens

« Cocon » encadre la pochette de l'app suivie. Ailleurs — la liste des titres de Deezer, une
autre app — il n'encadrerait rien du tout. Dès que l'app de musique n'est pas celle à l'écran, ce
style bascule donc sur celui choisi dans « Hors de l'app de musique » (barres ou contour), qui
sont tous deux accrochés aux bords de l'écran et donc aussi justes par-dessus n'importe quoi.

Deux nuances : ça demande l'autorisation d'accès aux données d'utilisation, et faute de pouvoir
établir quelle app est devant, le style n'est pas changé — on ne dégrade rien au jugé. Et si
« Masquer hors de l'app de musique » est activé, la question ne se pose plus : la superposition
est déjà masquée dans ce cas, ce qui est précisément pourquoi ce réglage est désactivé par
défaut.

Ce que Vizuzik ne sait pas, en revanche, c'est *quel écran* de Deezer est affiché : rien ne
distingue sa page de lecture de sa liste de titres depuis une fenêtre de superposition.

### Ce qui le rend fluide

Le halo (chaque brin repassé en traits larges et très transparents) a été supprimé : à lui seul il
représentait l'essentiel du coût d'une image, environ **25 millions de pixels antialiasés avec
shader par seconde**. Un effet de bord qui saccade est pire qu'un effet de bord qui ne rayonne
pas. Les brins sont passés de 22 à 12 et sont tracés en trois paliers de luminosité groupés — six
tracés par image au lieu de soixante-six, un `Path` pouvant contenir autant de sous-chemins qu'on
veut. Au total le remplissage est divisé par quatre.

Le ruban est par ailleurs positionné contre l'**écran** et non contre sa propre fenêtre :
celle-ci est déclarée en `NO_LIMITS` et déborde dans l'encoche, donc sa taille et son origine ne
correspondent pas à celles de l'écran — s'y fier décalait le ruban de 170 px sous la pochette. Les
dimensions réelles viennent de `WindowManager`, et `getLocationOnScreen()` ramène le tout dans le
repère de la vue.

Tout ce qui ne dépend que de l'angle — le rayon de la superellipse (trois `Math.pow` à chaque
point), le vecteur unitaire, les sinus et cosinus des trois fréquences de lobe — est calculé
**une seule fois**, au chargement de la classe, dans des tables. C'était auparavant recalculé
pour chaque brin de chaque image alors que c'est identique d'un brin à l'autre et ne change
jamais : environ 226 000 `Math.pow` et 377 000 appels trigonométriques par seconde sur le thread
principal, ce qui se voyait à l'œil. Il ne reste que six appels trigonométriques par brin, pour
son propre déphasage, l'identité de la somme d'angles transformant le reste en
multiplications-additions. Le spectre, lui, est échantillonné une fois par image et non une fois
par brin. La boucle tourne du coup à 30 images/s au lieu de 24.

## Savoir quelle app est à l'écran

Deux réglages en dépendent : le repli du Cocon (voir plus bas), et « Masquer hors de l'app de
musique », qui fait disparaître la superposition entière dès qu'on quitte Deezer au lieu de la
faire basculer sur le style de repli. Ce dernier est **désactivé par défaut** : les deux se
contredisent, et masquer l'emportait — le repli ne pouvait alors jamais s'afficher.

Une fenêtre de superposition ne voit pas ce qu'il y a en dessous, et la session multimédia ne dit
rien de l'app affichée — un Deezer en pause en arrière-plan y ressemble trait pour trait à un
Deezer au premier plan. La seule façon de le savoir sans service d'accessibilité est
`UsageStatsManager`, qui demande l'autorisation spéciale **« Accès aux données d'utilisation »**
(voir `ForegroundApp.java`). Elle est accordée dans un écran système, comme les deux autres autorisations spéciales de l'app,
et elle est demandée **une fois, au premier lancement qui atteint l'écran lecteur** — donc au
même moment que le micro et l'accès aux notifications, et non plus seulement depuis le panneau.
Une seule fois : un écran système qui se rouvre à chaque lancement est ce qui fait désinstaller
une app. La réponse est mémorisée sous `vizuzik:usageAccessAsked`, et le bouton du panneau reste
là pour changer d'avis.

Tant qu'elle n'est pas accordée, rien ne peut répondre à la question : le contour **reste visible
partout** et le Cocon ne bascule sur rien, plutôt que de se cacher ou de se dégrader au jugé. Le
panneau affiche alors un bouton **« Autoriser l'accès aux données d'utilisation »**, et le
libellé du réglage explique la situation.

Ce bouton existe parce que la première version ne demandait l'autorisation qu'au basculement de
l'interrupteur : celui-ci ayant une valeur par défaut, quelqu'un d'accord avec elle n'y touchait
jamais et n'était donc jamais sollicité — les deux fonctionnalités ne faisaient alors rien, en
silence. Une autorisation dont dépend une fonctionnalité doit pouvoir être accordée là où on
constate qu'elle manque.

Rien d'autre n'est lu de ces statistiques : seulement le nom du dernier paquet passé au premier
plan, jamais conservé ni envoyé nulle part.

## Jusqu'où va la superposition

La fenêtre couvre tout l'écran, **encoche et barre d'état comprises**. Ça ne va pas de soi :
sans `layoutInDisplayCutoutMode`, Android met la fenêtre en retrait de l'encoche en portrait et
les barres s'arrêtent en haut de l'app plutôt qu'en haut du téléphone, avec une bande vide
au-dessus. `FLAG_LAYOUT_NO_LIMITS` ne suffit pas : le mode d'encoche est une décision distincte.

Une limite subsiste, du système : `TYPE_APPLICATION_OVERLAY` est **sous** la barre d'état dans
l'ordre des fenêtres. L'horloge et les icônes système restent donc dessinées par-dessus les
barres ; ce qui est gagné, c'est la bande elle-même, transparente aussi bien sur Deezer que sur
l'écran d'accueil.

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
