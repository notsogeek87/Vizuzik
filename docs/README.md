# Documentation Vizuzik

Vizuzik est un écran plein écran Android (Capacitor + Vite) qui affiche et pilote la lecture
Deezer en cours, avec un moteur visuel réactif à l'audio.

## Sommaire

### Architecture
- [Moteur visuel réactif](architecture/2026-09-03-moteur-visuel-reactif.md) — pourquoi un seul
  canvas plein écran, une seule boucle d'animation et trois variables CSS pilotent toute l'app.
- [Le consentement de capture audio comme parcours](architecture/2026-09-03-consentement-capture-audio.md)
  — comment la fenêtre système Android, impossible à supprimer, n'est plus croisée qu'une fois
  par lancement.

### API interne
- [`Visualizer`](api/visualizer.md) — le moteur de rendu des cinq scènes.
- [`extractPalette`](api/palette.md) — extraction de la palette depuis la pochette.
- [Position de lecture et recherche](api/lecture.md) — `getPosition`, `seek`, et la barre de
  progression.

### Guides
- [Gestes et contrôles](guides/gestes.md) — tap, swipe et barre de progression.
- [Les cinq modes de visualisation](guides/modes-de-visualisation.md) — ce que voit
  l'utilisateur et comment il en change.
- [Activer le son réel](guides/capture-audio.md) — capture de la sortie audio de Deezer :
  parcours d'activation, lecture du badge, et ce qui ne redéclenche plus la fenêtre système.
