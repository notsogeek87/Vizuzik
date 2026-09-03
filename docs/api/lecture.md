# Position de lecture, durée et recherche

`src/progress.js` · `DeezerMediaPlugin.java`

**Pour qui :** toute personne qui touche à la barre de progression ou aux données de lecture
remontées par le plugin natif.

## Surface du plugin

Le plugin expose désormais la position et la durée, et permet de se déplacer dans le morceau.

| Méthode | Retour / paramètre | Notes |
|---|---|---|
| `getNowPlaying()` | ajoute `duration`, `position` (ms) et `canSeek` | La position est résolue contre l'état **courant** de la session, pas celui figé au moment où le titre a été publié. |
| `getPosition()` | `{ active, position, duration, isPlaying, canSeek }` | Volontairement sans la pochette : cet appel est répété toutes les 5 s et ré-encoder le bitmap en base64 à chaque fois serait du gaspillage. |
| `seek({ position })` | position en ms | Rejette si la position est absente ou négative. |

L'événement `nowPlayingChanged` transporte les mêmes champs que `getNowPlaying()`.

### Pourquoi la position doit être extrapolée

Un `PlaybackState` Android porte la position telle qu'elle était **au dernier moment où la
session l'a mise à jour**, pas telle qu'elle est maintenant. Sans extrapolation, la barre
resterait figée entre deux mises à jour de la session. `DeezerMediaBridge.resolvePosition()`
avance donc la valeur depuis `getLastPositionUpdateTime()`, à la vitesse de lecture déclarée,
et uniquement si l'état est `STATE_PLAYING`.

### `canSeek`

Vrai seulement si la session Deezer annonce `ACTION_SEEK_TO`. Quand c'est faux, la barre
s'affiche mais n'est pas manipulable : pas de poignée, pas de glisser.

## `PlaybackProgress` (côté web)

```js
import { PlaybackProgress } from "./progress.js";

const progress = new PlaybackProgress(
  { root, bar, elapsed, total },
  { onSeek: (positionMs) => DeezerMedia.seek({ position: positionMs }) }
);
```

| Méthode | Rôle |
|---|---|
| `setTrack({ position, duration, isPlaying, canSeek })` | Ré-ancre l'horloge locale. Ignoré pendant un glisser en cours : la position pointée par le doigt est plus récente que celle qui revient du natif. |
| `positionNow()` | Position courante en ms, extrapolée depuis l'ancre. |
| `render(force)` | Met à jour le DOM. S'auto-limite à une mise à jour toutes les 120 ms — une barre qui avance d'un pixel par seconde n'a rien à gagner à 60 images par seconde. |

L'horloge tourne localement entre deux ancres ; `src/main.js` la ré-ancre toutes les 5 s via
`getPosition()`, ce qui suffit largement à ce que la dérive ne devienne jamais visible.

Le rendu passe par `--p` (0..1) sur le conteneur : le remplissage est un `scaleX`, donc composé
par le GPU au lieu de relayouter la ligne à chaque rafraîchissement.
