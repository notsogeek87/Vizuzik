# ADR — Moteur visuel réactif

**Date :** 2026-09-03
**Statut :** Accepté

## Contexte

La première version affichait la pochette, ou bien un petit canvas carré (barres / radial)
posé à la place de la pochette. Chaque effet vivait dans son coin : le fond était une image
floutée statique, les contrôles ne réagissaient à rien, et la pochette disparaissait dès qu'on
passait en mode visualisation.

L'objectif de cette refonte est que **toute la page réagisse à la musique comme un seul
objet**, tout en restant tenable sur une WebView Android de milieu de gamme.

## Décisions

### 1. Un seul canvas plein écran, derrière toute l'interface

`#fx` est en `position: fixed; inset: 0` avec un `z-index` sous le contenu. Les scènes ne sont
plus confinées à un carré : le spectre occupe toute la largeur, l'aurore traverse l'écran, la
nébuleuse tourne autour de la pochette. Un `.scrim` en dégradé et une plaque d'ombre derrière
le bloc titre/artiste garantissent la lisibilité du texte par-dessus.

### 2. Une seule boucle d'animation pour le canvas **et** le DOM

`Visualizer` possède le seul `requestAnimationFrame` de l'application. À chaque image, son
callback `onFrame` écrit trois propriétés CSS personnalisées sur `:root` :

| Variable  | Plage | Sens |
|-----------|-------|------|
| `--beat`  | 0..1  | Impulsion décroissante : sur chaque kick détecté avec la capture, sinon uniquement sur un évènement réel — voir [Le rythme hors capture](2026-09-03-rythme-hors-capture.md) |
| `--level` | 0..1  | Volume global lissé |
| `--bass`  | 0..1  | Énergie des basses lissée |

Le CSS s'en sert pour la lueur de la pochette, l'échelle du fond, le halo du bouton lecture.
Conséquence : aucune deuxième boucle, aucun `setInterval` d'animation, et le DOM reste
synchronisé avec le canvas à l'image près.

Les valeurs sont quantifiées à 1/50 avant écriture : au-delà l'œil ne suit plus, et cela évite
d'invalider les styles à chaque image dans les passages calmes.

### 3. La palette vient de la pochette, pas d'une constante

`extractPalette()` réduit la pochette à 32×32, regroupe les pixels par tranches de teinte et de
luminosité, puis retient trois accents distants d'au moins 32° de teinte. Ces trois couleurs
alimentent à la fois le canvas et les variables CSS `--c1 --c2 --c3`. Le visualiseur interpole
en continu vers la nouvelle palette : un changement de titre fond d'une ambiance à l'autre au
lieu de sauter.

### 4. Détection de beat adaptative

Un seuil fixe fait clignoter les morceaux forts et laisse les ballades inertes. La détection
compare l'énergie des basses à sa propre moyenne glissante (48 images) : est un beat toute
valeur dépassant `moyenne × 1,32`, avec un intervalle minimum de 190 ms.

Elle ne tourne que sur du **son réellement capté**. Sans capture, il n'y a pas de rythme à
détecter et le moteur n'en invente pas : lancée sur les vagues du régime ambiant, elle ne
ferait que redécouvrir ses propres oscillateurs et les transformer en métronome.

### 5. Bloom par recopie floutée, pas par `shadowBlur`

`ctx.shadowBlur` par forme coûte cher et se cumule mal. À la place, l'image finie est recopiée
dans un tampon demi-résolution, puis repeinte par-dessus elle-même à travers un
`ctx.filter = blur(...)` en composition `lighter`. Une seule passe de dessin, deux copies, et
les zones lumineuses saturent vers le blanc comme une vraie lumière. Si `ctx.filter` n'est pas
supporté (vieille WebView), le bloom est simplement sauté.

### 6. Compromis de performance assumés

- Le `devicePixelRatio` est plafonné à 2 : un tampon 3× ne se voit pas une fois tout flouté.
- Les taches de couleur du fond (`.ambient__blobs`) n'ont **pas** de `filter: blur()` : ce sont
  déjà des dégradés radiaux doux, et flouter trois calques en mouvement permanent coûtait
  environ un tiers du budget d'image pour un résultat visuellement identique.
- Les traînées de la nébuleuse vivent sur leur propre tampon : les dessiner sur le canvas
  principal ferait boucler le bloom sur lui-même et délaverait l'écran en une seconde.

## Conséquences

- Ajouter une scène = une méthode `_drawX(ctx)` plus une entrée dans `VISUAL_STYLES` ; toute
  l'analyse audio est déjà disponible (`bars`, `peaks`, `bass`/`mid`/`treble`, `beatEnergy`).
- Toute la réactivité DOM passe par trois variables : pas de classe à poser par image, pas de
  style recalculé sur l'arbre entier.
- Le canvas doit connaître la position de la pochette (`setFocusElement()`), sinon les scènes
  centrées se décaleraient pendant la transition de 0,7 s entre les modes.
