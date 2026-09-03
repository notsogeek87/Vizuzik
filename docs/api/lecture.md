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
| `seek({ position })` | position en ms | Rejette si la position est absente ou négative. Voir le piège `getLong` ci-dessous. |

L'événement `nowPlayingChanged` transporte les mêmes champs que `getNowPlaying()`.

### Pourquoi la position doit être extrapolée

Un `PlaybackState` Android porte la position telle qu'elle était **au dernier moment où la
session l'a mise à jour**, pas telle qu'elle est maintenant. Sans extrapolation, la barre
resterait figée entre deux mises à jour de la session. `DeezerMediaBridge.resolvePosition()`
avance donc la valeur depuis `getLastPositionUpdateTime()`, à la vitesse de lecture déclarée,
et uniquement si l'état est `STATE_PLAYING`.

### Piège : `PluginCall.getLong()` ne lit pas les entiers JS

`getLong()` ne renvoie une valeur que si l'objet bridgé est **littéralement** une instance de
`Long` :

```java
if (value instanceof Long) return (Long) value;
return defaultValue;   // null
```

Or une position en millisecondes (`74123`) traverse le pont en `Integer`, et une position
fractionnaire en `BigDecimal` — jamais en `Long` sauf au-delà de la plage `int`, soit environ
24 jours de lecture. `getLong("position")` renvoyait donc `null` pour **toutes** les positions
réelles, et chaque recherche était rejetée avant d'atteindre Deezer. La lecture se fait
maintenant via `call.getData().optLong("position", -1)`, qui convertit le type numérique quel
qu'il soit.

Le même piège vaut pour `getInt()` et `getDouble()` : préférer `getData().optLong()` /
`optInt()` / `optDouble()` pour tout nombre venant du JS.

### Vérifier qu'une recherche a pris

`MediaSession.seekTo()` est sans retour : une session peut accepter l'appel puis l'ignorer.
`requestSeek()` (dans `src/main.js`) revérifie donc la position réelle 900 ms après, et si
l'écart dépasse 4 s, remet la barre à sa vraie place et affiche « Deezer ignore le
déplacement ». Un rejet immédiat affiche « Déplacement refusé ». Une barre qui ment sur la
position atteinte est pire qu'un message honnête.

### `canSeek` — indicatif, pas bloquant

Vrai seulement si la session Deezer annonce `ACTION_SEEK_TO`. **Ce drapeau ne conditionne pas
l'interface :** en pratique les lecteurs acceptent couramment `seekTo()` sans l'annoncer, et
s'en servir comme verrou rendait la barre totalement inerte sur l'appareil de test. La barre est
donc manipulable dès qu'une durée est connue ; si une recherche est réellement refusée, le
ré-ancrage suivant (5 s) remet la barre en place.

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
| `setTrack({ position, duration, isPlaying })` | Ré-ancre l'horloge locale. Ignoré pendant un glisser en cours : la position pointée par le doigt est plus récente que celle qui revient du natif. |
| `positionNow()` | Position courante en ms, extrapolée depuis l'ancre. |
| `render(force)` | Met à jour le DOM. S'auto-limite à une mise à jour toutes les 120 ms — une barre qui avance d'un pixel par seconde n'a rien à gagner à 60 images par seconde. |

L'horloge tourne localement entre deux ancres ; `src/main.js` la ré-ancre toutes les 5 s via
`getPosition()`, ce qui suffit largement à ce que la dérive ne devienne jamais visible.

Le rendu passe par `--p` (0..1) sur le conteneur : le remplissage est un `scaleX`, donc composé
par le GPU au lieu de relayouter la ligne à chaque rafraîchissement.

### Cible tactile

Les écouteurs de pointeur sont posés sur le **bloc rembourré** (`#progress`, ~52 px de haut),
pas sur la barre visible (6 px) : une bande de 6 px est intouchable au doigt. La géométrie, elle,
est lue sur la barre, si bien qu'un appui n'importe où dans le bloc — y compris sur la ligne des
minutages — se projette correctement sur la barre.
