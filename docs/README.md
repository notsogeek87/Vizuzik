# Documentation Vizuzik

Vizuzik est un écran plein écran Android (Capacitor + Vite) qui affiche et pilote la lecture
Deezer ou Spotify en cours, avec un moteur visuel réactif à l'audio.

## Sommaire

### Architecture
- [Moteur visuel réactif](architecture/2026-09-03-moteur-visuel-reactif.md) — pourquoi un seul
  canvas plein écran, une seule boucle d'animation et trois variables CSS pilotent toute l'app.
- [Le rythme hors capture](architecture/2026-09-03-rythme-hors-capture.md) — pourquoi
  l'application n'invente plus de tempo quand elle n'entend pas la musique, et ce qui anime
  l'écran à la place.
- [Choisir puis lancer l'app de musique soi-même](architecture/2026-09-03-lancement-automatique-deezer.md)
  — comment Vizuzik décide s'il suit Deezer ou Spotify, pourquoi il ne demande qu'en cas
  d'ambiguïté réelle, et pourquoi il ouvre l'app choisie au lancement plutôt que d'attendre
  qu'on le fasse.
- [Une seule source audio](architecture/2026-09-07-source-audio-unique.md) — pourquoi le micro, le
  « son réel » et le mode ambiance ont disparu au profit d'une source unique attachée à la session
  audio de l'app de musique, sans aucune fenêtre de consentement.
- [Edge Visualizer : le contour lumineux, sans micro cette fois](architecture/2026-09-06-edge-visualizer.md)
  — l'overlay décoratif dessiné sur les bords de l'écran par-dessus l'app de musique, ce qui a
  été retenu (et pourquoi) d'une première tentative retirée, et le panneau de réglages qui
  l'accompagne.

### API interne
- [`Visualizer`](api/visualizer.md) — le moteur de rendu des scènes.
- [`extractPalette`](api/palette.md) — extraction de la palette depuis la pochette.
- [Position de lecture et recherche](api/lecture.md) — `getPosition`, `seek`, et la barre de
  progression.

### Guides
- [Gestes et contrôles](guides/gestes.md) — tap, swipe et barre de progression.
- [Les sept modes de visualisation](guides/modes-de-visualisation.md) — ce que voit
  l'utilisateur et comment il en change.
- [Edge Visualizer](guides/edge-visualizer.md) — le contour lumineux par-dessus l'app de musique :
  parcours d'activation, ce qui le fait réagir, le panneau de réglages, et ses limites.

### Archives
Documentation conservée pour la trace du raisonnement, mais qui ne décrit plus le code actuel.
- [Le consentement de capture audio comme parcours](legacy/2026-09-03-consentement-capture-audio.md)
- [Activer le son réel](legacy/capture-audio.md)

