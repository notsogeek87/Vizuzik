# Documentation Vizuzik

Vizuzik est un écran plein écran Android (Capacitor + Vite) qui affiche et pilote la lecture
Deezer ou Spotify en cours, avec un moteur visuel réactif à l'audio.

## Sommaire

### Architecture
- [Moteur visuel réactif](architecture/2026-09-03-moteur-visuel-reactif.md) — pourquoi un seul
  canvas plein écran, une seule boucle d'animation et trois variables CSS pilotent toute l'app.
- [Le consentement de capture audio comme parcours](architecture/2026-09-03-consentement-capture-audio.md)
  — comment la fenêtre système Android, impossible à supprimer, n'est plus croisée qu'une fois
  par lancement.
- [Le rythme hors capture](architecture/2026-09-03-rythme-hors-capture.md) — pourquoi
  l'application n'invente plus de tempo quand elle n'entend pas la musique, et ce qui anime
  l'écran à la place.
- [Choisir puis lancer l'app de musique soi-même](architecture/2026-09-03-lancement-automatique-deezer.md)
  — comment Vizuzik décide s'il suit Deezer ou Spotify, pourquoi il ne demande qu'en cas
  d'ambiguïté réelle, et pourquoi il ouvre l'app choisie au lancement plutôt que d'attendre
  qu'on le fasse.

### API interne
- [`Visualizer`](api/visualizer.md) — le moteur de rendu des scènes.
- [`extractPalette`](api/palette.md) — extraction de la palette depuis la pochette.
- [Position de lecture et recherche](api/lecture.md) — `getPosition`, `seek`, et la barre de
  progression.

### Guides
- [Gestes et contrôles](guides/gestes.md) — tap, swipe et barre de progression.
- [Les six modes de visualisation](guides/modes-de-visualisation.md) — ce que voit
  l'utilisateur et comment il en change.
- [Activer le son réel](guides/capture-audio.md) — capture de la sortie audio de l'app suivie
  (Deezer ou Spotify) : parcours d'activation, lecture du badge, et ce qui ne redéclenche plus
  la fenêtre système.
