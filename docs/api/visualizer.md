# `Visualizer` — moteur de rendu

`src/visualizer.js`

**Pour qui :** toute personne qui ajoute une scène, branche une nouvelle source audio, ou
change la réactivité de l'interface.

Une seule instance existe, créée dans `src/main.js`. Elle possède l'unique boucle
`requestAnimationFrame` de l'application.

Le moteur a **deux régimes**, et la différence est une règle de conception : il ne devine
jamais de tempo. Voir [Le rythme hors capture](../architecture/2026-09-03-rythme-hors-capture.md).

```js
import { Visualizer, VISUAL_STYLES } from "./visualizer.js";

const visualizer = new Visualizer(document.getElementById("fx"));
visualizer.setFocusElement(document.getElementById("disc"));
visualizer.onFrame = ({ beat, level, bass, treble }) => { /* ... */ };
visualizer.start();
```

## Cycle de vie

| Méthode | Effet |
|---------|-------|
| `start()` | Dimensionne les tampons et démarre la boucle. Idempotent. |
| `stop()` | Arrête la boucle. **Ne vide pas** le canvas : la dernière image reste affichée. |
| `clear()` | Vide canvas, traînées, particules et ondes. À appeler après `stop()` en quittant l'écran lecteur. |
| `destroy()` | `stop()` + retrait des écouteurs `resize` / `orientationchange`. |

## Entrées

| Méthode | Description |
|---------|-------------|
| `setLevels(levels)` | Spectre temps réel, 32 valeurs 0..1, fourni par `AudioCaptureService`. Au-delà de 500 ms sans appel, le moteur repasse en régime ambiant. |
| `setPlaying(bool)` | En pause, les barres retombent au repos et le disque cesse de tourner. |
| `setStyle(style)` | Une valeur de `VISUAL_STYLES`. Réinitialise particules, ondes et traînées. |
| `setPalette(palette)` | Objet renvoyé par `extractPalette()`. Les couleurs sont rejointes par interpolation, pas d'un coup. Ne déclenche **pas** d'onde de choc : c'est `pulse()` qui marque le changement de morceau. |
| `pulse(strength)` | Impulsion volontaire, `strength` 0..1 : monte `beatEnergy`, émet une onde et une gerbe de particules. À appeler pour un évènement **réel** (changement de morceau, lecture/pause, glissement, changement de mode). En régime ambiant, c'est la seule source d'impulsion. |
| `setFocusElement(el)` | Élément DOM traité comme centre de composition (le disque). |
| `updateFocus()` | Relit la position de cet élément — à appeler pendant les transitions de mode. |

## Sorties

| Membre | Description |
|--------|-------------|
| `onFrame(state)` | Callback par image : `{ beat, level, bass, treble }`, tous 0..1. |
| `displayColor(i, out?)` | Couleur `i` de la palette telle qu'elle doit être affichée maintenant : interpolée vers le morceau en cours, puis décalée par le voyage ambiant. Remplit et renvoie un tableau de travail réutilisé — copier avant de le garder. `src/main.js` s'en sert pour écrire `--c1`/`--c2`/`--c3`, afin que le DOM et le canvas ne soient jamais de deux teintes différentes. |
| `captureStatus` | `"live"`, `"silent"` ou `"simulated"` — alimente le badge de l'interface. |
| `isLive` | `true` tant que des niveaux réels arrivent : le régime dans lequel tourne le moteur. |

`"silent"` signifie que des niveaux arrivent bien mais restent à plat depuis plus de 3 s :
en pratique, Deezer a refusé la capture de lecture et Android renvoie du silence.

## Les deux régimes

| | **Live** (`setLevels()` alimenté) | **Ambiant** (aucune capture, ou plus de 500 ms sans niveaux) |
|---|---|---|
| Spectre | Le vrai, lissage asymétrique : attaque immédiate, retombée lente. | Trois oscillateurs par bande, de périodes sans multiple commun (≈30 s, ≈48 s, ≈82 s), lissage lent et symétrique. Rien ne peut « taper ». |
| `beatEnergy` | Détection de kick adaptative (`_detectBeat`). | Aucune détection. Ne monte que sur `pulse()`. |
| Couleurs | Palette du morceau, fixe par élément. | La palette **voyage** : `colorShift` avance d'une couleur toutes les 26 s, et toute la scène glisse d'un accent vers le suivant. |
| Impulsions | Ondes et particules sur chaque kick. | Uniquement sur `pulse()`. |

Le régime ambiant n'est pas un mode dégradé mais un parti pris : une pulsation inventée est
comparée par l'oreille à celle qu'elle entend, et une pulsation fausse se lit comme une panne
là où l'absence de pulsation se lit comme du calme.

## Ajouter une scène

1. Ajouter le nom à `VISUAL_STYLES`.
2. Écrire `_drawMaScene(ctx)` et l'appeler depuis le `switch` de `_draw()`.
3. Ajouter une icône SVG `.icon-ma-scene` dans `index.html` et la règle
   `body[data-mode="ma-scene"] .mode-toggle .icon-ma-scene { display: block; }`.
4. Ajouter un libellé dans `MODE_LABELS` (`src/main.js`).

Données d'analyse disponibles dans la méthode de dessin :

| Champ | Description |
|-------|-------------|
| `this.bars[32]` | Spectre lissé (attaque rapide, retombée lente). |
| `this.peaks[32]` | Maxima glissants avec chute par gravité. |
| `this.bass` / `this.mid` / `this.treble` | Énergies par zone. |
| `this.energy` | Volume global lissé. |
| `this.beatEnergy` | 1 sur le kick (live) ou sur `pulse()`, décroissance 0,9 par image. |
| `this.isLive` | Vrai si le spectre vient d'une vraie capture — à consulter avant de se fier au rythme. |
| `this.focus` | `{ x, y, r }` de la pochette à l'écran. |
| `this._rgba(i, a)` | Couleur `i` de la palette (interpolée **et** décalée par le voyage ambiant), opacité `a`. |
| `this._mirroredBand(slot)` | Spectre replié sur 64 fentes : graves au centre, aigus aux bords. |

Le contexte est déjà en composition `lighter` quand la scène est appelée : les formes
s'additionnent, et le bloom est appliqué après.

`cassette` déroge à cette recette : c'est une illustration fixe, plein écran, portée par
`index.html`/`style.css` plutôt que par le canvas (voir `body[data-mode="cassette"] .cassette`
dans `style.css`). Son `case` dans `_draw()` ne fait rien — `.fx` est caché par CSS dans ce
mode — et elle est exclue de `_burstParticles()` pour rester réellement fixe.
