# Le consentement de capture audio comme parcours, pas comme fenêtre

**Statut :** adopté · **Date :** 2026-09-03 · **Concerne :**
`src/main.js`, `DeezerMediaPlugin.java`, `AudioLevelsBridge.java`

## Le problème

Faire réagir les visuels au son de Deezer passe par `AudioPlaybackCapture`, qui exige un
`MediaProjection` — donc la fenêtre système « enregistrer ou caster l'écran ». Cette fenêtre est
brutale : elle parle d'enregistrement d'écran alors qu'on ne lit que la piste audio, elle ne
ressemble en rien au reste de l'application, et elle arrivait sans préavis.

Pire, on la revoyait sans arrêt. Trois causes :

1. `showScreen()` coupait la capture dès que l'écran lecteur était masqué — c'est-à-dire à
   **chaque pause Deezer ou blanc entre deux titres**. La musique revenait, la fenêtre aussi.
2. L'état de la capture ne vivait que dans des variables JavaScript. Une webview recréée les
   perdait alors que `AudioCaptureService` tournait toujours : le badge reproposait l'activation
   d'une capture déjà active.
3. Aucune mémoire d'un lancement à l'autre : il fallait retrouver le badge, puis subir la
   fenêtre, à chaque démarrage.

## Ce qui n'était pas une option

Supprimer la fenêtre. `CAPTURE_AUDIO_OUTPUT` est `signature|privileged` : hors de portée d'une
application installée normalement. Un service d'accessibilité ne donne accès qu'au contenu des
fenêtres et aux gestes, jamais au flux audio — et détourner l'accessibilité à cette fin est
explicitement interdit par les règles du Play Store. Le micro (`RECORD_AUDIO`) éviterait bien
`MediaProjection`, mais capte la pièce et non la sortie audio : inutilisable au casque, et
pollué par les voix ambiantes. Le consentement reste donc obligatoire ; seule sa **fréquence**
est négociable.

## La décision

Traiter le consentement comme un parcours en trois temps, plutôt que comme un appel natif.

**1. Préparer.** Au tout premier appui sur le badge, un écran de l'application explique ce que
le son sert à faire, que rien n'est enregistré ni envoyé, et quel bouton toucher dans la fenêtre
Android qui va suivre. Montré une seule fois : ensuite, l'utilisateur sait, et un écran de plus
ne serait qu'un appui de plus.

**2. Mémoriser.** L'issue est stockée (`localStorage`, clé `vizuzik:realAudio`) :

- `"on"` — au lancement suivant, dès qu'un morceau est affiché, le consentement est demandé
  directement. Un appui économisé, et la fenêtre arrive à un moment où l'utilisateur regarde
  déjà son lecteur.
- `"off"` — un refus est une réponse, pas un échec : plus rien ne s'ouvre spontanément.

La demande automatique est verrouillée par quatre garde-fous : au plus une par session, jamais
en arrière-plan, jamais hors de l'écran lecteur, et jamais avant que l'état natif soit connu.

**3. Ne plus jamais redemander inutilement.** La capture survit désormais aux pauses Deezer —
`showScreen()` ne l'arrête plus, le service s'arrêtant de lui-même quand l'application quitte
les récents. Et l'état natif devient la source de vérité, via deux ajouts :

| Ajout | Rôle |
|---|---|
| `AudioLevelsBridge.isCapturing()` | Drapeau `volatile` posé par `AudioCaptureService` au démarrage de la capture, retombé dans `publishStopped()`. |
| `DeezerMediaPlugin.getCaptureState()` | Retourne `{ supported, running }`. Interrogé au lancement et à chaque retour au premier plan. |

`startVisualizerCapture()` court-circuite en plus la fenêtre quand une capture tourne déjà — il
résout `{ alreadyRunning: true }` au lieu de rouvrir un dialogue qui, accepté, détruirait la
projection en cours.

## Conséquences

- Cas nominal : **une seule fenêtre système par lancement de l'application**, contre une par
  pause auparavant ; et zéro si le service a survécu.
- Le badge dit l'état réel (`● Son réel prêt` en pause, `signal faible` si les niveaux se
  taisent) au lieu de rejouer les intitulés d'une activation à refaire.
- Sur un appareil incapable de capturer, le badge disparaît au lieu d'afficher un échec.
- Le web fait confiance au natif pour l'état : toute future voie de capture devra alimenter
  `AudioLevelsBridge` pour que l'interface la voie.
- Compatibilité descendante : un binaire natif plus ancien, sans `getCaptureState()`, retombe
  simplement sur le parcours manuel par le badge.
