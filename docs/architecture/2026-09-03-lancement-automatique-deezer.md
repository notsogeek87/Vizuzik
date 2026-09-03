# Choisir puis lancer l'app de musique soi-même

**Statut :** adopté · **Date :** 2026-09-03 · **Concerne :**
`src/main.js`, `index.html`, `DeezerMediaPlugin.java`, `MusicApps.java`,
`MusicAppPreference.java`, `NowPlayingListenerService.java`, `AudioCaptureService.java`

## Le problème

Vizuzik est un écran plein écran qui affiche et pilote la lecture Deezer **ou Spotify** en
cours — mais il ne la déclenche pas, et il ne sait pas non plus, au tout premier lancement,
laquelle des deux apps regarder. Tant que rien n'a démarré, l'utilisateur regarde l'écran
« aucune lecture » ou l'écran de permission, et doit quitter Vizuzik pour aller ouvrir l'app de
musique lui-même avant de revenir. Deux allers-retours pour un geste que Vizuzik peut faire à sa
place — à condition de savoir laquelle des deux ouvrir.

## La décision

### Quelle app suivre

Résolu une fois par installation, avant tout le reste, par `resolveMusicApp()` :

1. Un choix déjà mémorisé (`localStorage`, clé `vizuzik:musicApp`, valeur `"deezer"` ou
   `"spotify"`) est repris tel quel, tant que l'app choisie est toujours installée.
2. Sinon, `DeezerMediaPlugin.detectMusicApps()` dit lesquelles des deux sont installées
   (`PackageManager#getLaunchIntentForPackage` sur `deezer.android.app` et
   `com.spotify.music`). Une seule installée : c'est elle, sans rien demander.
3. Aucune installée : rien à suivre, `resolveMusicApp()` renvoie `null` et le reste du parcours
   se comporte comme avant (écran vide, en attente).
4. **Les deux installées : le seul cas où Vizuzik ne peut pas deviner.** L'écran `#app-select`
   (deux boutons, « Deezer » / « Spotify ») demande une fois, et seulement cette fois-là ; la
   réponse est aussitôt mémorisée pour ne plus jamais redemander.

Le choix est ensuite miroité côté natif via `DeezerMediaPlugin.setMusicAppTarget()`, qui l'écrit
dans `MusicAppPreference` (une `SharedPreferences` toute simple). C'est nécessaire parce que
`NowPlayingListenerService` (qui repère la session média active) et `AudioCaptureService` (qui
borne la capture audio à l'UID de la bonne app) tournent côté natif, sans accès au `localStorage`
de la webview. `MusicApps.java` centralise les deux noms de paquet connus et les clés qui les
désignent (`"deezer"` / `"spotify"`) des deux côtés du pont.

Tant qu'aucune cible n'est encore configurée côté natif (l'instant précédant la toute première
résolution), `NowPlayingListenerService` accepte la session de n'importe laquelle des deux apps
connues plutôt que de rien afficher — pour que le cas courant (une seule des deux installée)
n'attende pas inutilement l'aller-retour vers le natif avant de montrer quelque chose.

### Lancer l'app choisie

Au tout premier écran de chaque lancement, si aucun titre n'est déjà affiché (`els.player`
masqué — que ce soit l'écran vide ou celui de permission), Vizuzik ouvre l'app choisie via
`DeezerMediaPlugin.openMusicApp({ app })`, qui résout l'intent de lancement du paquet
correspondant et démarre l'activité. Rien ne se passe si l'app n'est pas installée : la méthode
répond simplement `{ launched: false }` plutôt que d'échouer, ce que l'appel côté web ignore de
toute façon (ce cas ne devrait de toute manière plus se produire, `resolveMusicApp()` n'ayant
choisi que parmi les apps effectivement détectées).

**Une seule fois par lancement, jamais en cours de session.** L'appel vit dans la même IIFE de
démarrage que la résolution de l'app, pas dans `visibilitychange` ni dans aucune fonction
rappelée plus tard. Le réexécuter à chaque retour au premier plan aurait fait exactement ce que
Vizuzik essaie d'éviter ailleurs (voir
[Le consentement de capture audio comme parcours](2026-09-03-consentement-capture-audio.md)) :
reprendre le focus sur l'app de musique alors que l'utilisateur vient justement de revenir
regarder les visuels.

**Seulement si rien ne joue déjà.** Un titre déjà affiché signifie que l'app est déjà à sa place ;
relancer son intent d'ouverture volerait le focus à l'écran plein écran pour rien.

## Conséquence sur le consentement de capture

Aucun nouveau code de consentement n'était nécessaire : `maybeAutoRequestCapture()` (voir le
même document) demande déjà le consentement système dès qu'un titre est affiché, si
`vizuzik:realAudio` vaut `"on"`. En amorçant l'app plus tôt dans le parcours, ce lancement
automatique fait simplement arriver ce moment plus tôt aussi — sans changer ses garde-fous (une
demande par session, jamais en arrière-plan, jamais hors de l'écran lecteur), et sans se soucier
de savoir laquelle des deux apps tourne : `AudioCaptureService` résout déjà l'UID à capturer
dynamiquement, depuis la session active ou, à défaut, depuis `MusicAppPreference`.

## Conséquences

- Une seule des deux apps installée : elle est choisie sans qu'on demande rien, comme avant avec
  Deezer.
- Les deux installées, jamais choisi encore : un écran, une fois, puis plus jamais.
- Premier écran d'un lancement à froid, rien ne joue : l'app choisie s'ouvre toute seule,
  l'utilisateur n'a plus qu'à lancer un morceau.
- Un titre déjà en cours au lancement (webview relancée pendant que l'app tournait) : rien ne
  bouge, l'écran plein écran reste au premier plan.
- Retour dans Vizuzik après être passé sur l'app de musique en cours de session : jamais de
  relance, seul le tout premier écran du lancement déclenche l'ouverture.
- Désinstaller l'app suivie et n'en garder qu'une : au lancement suivant, le choix mémorisé n'est
  plus valide, `resolveMusicApp()` retombe sur l'app restante sans redemander.
