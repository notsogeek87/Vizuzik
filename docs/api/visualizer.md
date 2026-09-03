# `Visualizer` — moteur de rendu

`src/visualizer.js`

**Pour qui :** toute personne qui ajoute une scène, branche une nouvelle source audio, ou
change la réactivité de l'interface.

Une seule instance existe, créée dans `src/main.js`. Elle possède l'unique boucle
`requestAnimationFrame` de l'application.

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
| `setLevels(levels)` | Spectre temps réel, 32 valeurs 0..1, fourni par `AudioCaptureService`. Au-delà de 500 ms sans appel, le moteur repasse en spectre synthétique. |
| `setPlaying(bool)` | En pause, les barres retombent au repos et le disque cesse de tourner. |
| `setStyle(style)` | Une valeur de `VISUAL_STYLES`. Réinitialise particules, ondes et traînées. |
| `setPalette(palette)` | Objet renvoyé par `extractPalette()`. Déclenche aussi une onde de choc. |
| `setFocusElement(el)` | Élément DOM traité comme centre de composition (le disque). |
| `updateFocus()` | Relit la position de cet élément — à appeler pendant les transitions de mode. |

## Sorties

| Membre | Description |
|--------|-------------|
| `onFrame(state)` | Callback par image : `{ beat, level, bass, treble }`, tous 0..1. |
| `captureStatus` | `"live"`, `"silent"` ou `"simulated"` — alimente le badge de l'interface. |

`"silent"` signifie que des niveaux arrivent bien mais restent à plat depuis plus de 3 s :
en pratique, Deezer a refusé la capture de lecture et Android renvoie du silence.

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
| `this.beatEnergy` | 1 sur le kick, décroissance 0,9 par image. |
| `this.focus` | `{ x, y, r }` de la pochette à l'écran. |
| `this._rgba(i, a)` | Couleur `i` de la palette interpolée, opacité `a`. |
| `this._mirroredBand(slot)` | Spectre replié sur 64 fentes : graves au centre, aigus aux bords. |

Le contexte est déjà en composition `lighter` quand la scène est appelée : les formes
s'additionnent, et le bloom est appliqué après.
