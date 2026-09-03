# `extractPalette` — palette depuis la pochette

`src/palette.js`

**Pour qui :** toute personne qui touche à la colorimétrie de l'application.

```js
import { extractPalette } from "./palette.js";

const palette = await extractPalette(albumArtDataUri);
// { colors: [[r,g,b], [r,g,b], [r,g,b]], hue: 0..360, saturation: 0..100 }
```

Ne rejette jamais : une pochette absente, illisible ou en niveaux de gris renvoie une palette
de repli (indigo / violet / cyan) plutôt qu'une couleur boueuse.

## Fonctionnement

1. Réduction de l'image à 32×32.
2. Regroupement des pixels par tranche de teinte (20°) croisée avec une bande de luminosité,
   pour qu'un rouge vif et un bordeaux sombre ne se moyennent pas en une couleur qui ne
   correspond à ni l'un ni l'autre.
3. Score favorisant les tons moyens saturés — ceux qu'on perçoit comme « la couleur de cette
   pochette » —, au détriment des quasi-noirs et quasi-blancs.
4. Sélection gloutonne de trois teintes distantes d'au moins 32°, puis remontée de saturation
   et normalisation de la luminosité pour que l'interface ait de quoi briller.

## Consommateurs

- `visualizer.setPalette(palette)` — toutes les scènes du canvas.
- Les variables CSS `--c1 --c2 --c3` (triplets `"r, g, b"`), écrites dans `src/main.js` et
  utilisables en `rgba(var(--c1), 0.5)`.
