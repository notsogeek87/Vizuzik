# Lancer Deezer soi-même plutôt que d'attendre qu'il le soit

**Statut :** adopté · **Date :** 2026-09-03 · **Concerne :**
`src/main.js`, `DeezerMediaPlugin.java`

## Le problème

Vizuzik est un écran plein écran qui affiche et pilote la lecture Deezer en cours — mais il ne
la déclenche pas. Au lancement, tant que Deezer n'a rien à raconter, l'utilisateur regarde
l'écran « aucune lecture » ou l'écran de permission, et doit quitter Vizuzik pour aller ouvrir
Deezer lui-même avant de revenir. Deux allers-retours pour un geste que Vizuzik peut faire à sa
place.

## La décision

Au tout premier écran de chaque lancement, si aucun titre n'est déjà affiché (`els.player`
masqué — que ce soit l'écran vide ou celui de permission), Vizuzik ouvre Deezer lui-même via
`DeezerMediaPlugin.openDeezer()`, qui résout l'intent de lancement du paquet
`deezer.android.app` (`PackageManager#getLaunchIntentForPackage`) et démarre l'activité. Rien ne
se passe si Deezer n'est pas installé : la méthode répond simplement `{ launched: false }`
plutôt que d'échouer, ce que l'appel côté web ignore de toute façon.

**Une seule fois par lancement, jamais en cours de session.** L'appel vit dans la promesse de
`refresh()` au chargement du module, pas dans `visibilitychange` ni dans aucune fonction rappelée
plus tard. Le réexécuter à chaque retour au premier plan aurait fait exactement ce que Vizuzik
essaie d'éviter ailleurs (voir
[Le consentement de capture audio comme parcours](2026-09-03-consentement-capture-audio.md)) :
reprendre le focus sur Deezer alors que l'utilisateur vient justement de revenir regarder les
visuels.

**Seulement si rien ne joue déjà.** Un titre déjà affiché signifie que Deezer est déjà à sa
place ; relancer son intent d'ouverture volerait le focus à l'écran plein écran pour rien.

## Conséquence sur le consentement de capture

Aucun nouveau code de consentement n'était nécessaire : `maybeAutoRequestCapture()` (voir le
même document) demande déjà le consentement système dès qu'un titre est affiché, si
`vizuzik:realAudio` vaut `"on"`. En amorçant Deezer plus tôt dans le parcours, ce lancement
automatique fait simplement arriver ce moment plus tôt aussi — sans changer ses garde-fous
(une demande par session, jamais en arrière-plan, jamais hors de l'écran lecteur).

## Conséquences

- Premier écran d'un lancement à froid, rien ne joue : Deezer s'ouvre tout seul, l'utilisateur
  n'a plus qu'à lancer un morceau.
- Un titre déjà en cours au lancement (webview relancée pendant que Deezer tournait) : rien ne
  bouge, l'écran plein écran reste au premier plan.
- Retour dans Vizuzik après être passé sur Deezer en cours de session : jamais de relance, seul
  le tout premier écran du lancement déclenche l'ouverture.
