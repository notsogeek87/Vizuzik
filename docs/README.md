# Documentation Vizuzik

Vizuzik est un écran plein écran Android (Capacitor + Vite) qui affiche et pilote la lecture
Deezer en cours, avec un moteur visuel réactif à l'audio.

## Sommaire

### Architecture
- [Moteur visuel réactif](architecture/2026-09-03-moteur-visuel-reactif.md) — pourquoi un seul
  canvas plein écran, une seule boucle d'animation et trois variables CSS pilotent toute l'app.

### API interne
- [`Visualizer`](api/visualizer.md) — le moteur de rendu des cinq scènes.
- [`extractPalette`](api/palette.md) — extraction de la palette depuis la pochette.

### Guides
- [Les cinq modes de visualisation](guides/modes-de-visualisation.md) — ce que voit
  l'utilisateur et comment il en change.
- [Activer le son réel](guides/capture-audio.md) — capture de la sortie audio de Deezer.
