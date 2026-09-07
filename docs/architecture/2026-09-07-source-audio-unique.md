# Une seule source audio, et plus rien à choisir

**Pour qui / pourquoi.** Pour qui touche au moteur audio ou se demande où sont passés le micro, le
« son réel » et le mode ambiance. Cette décision remplace trois sources sélectionnables par une
seule, non configurable, partagée par le lecteur plein écran et l'overlay.

## Ce qu'il y avait avant

Un badge dans la barre du haut faisait tourner trois sources :

| Source | Mécanisme | Ce qu'elle valait |
| --- | --- | --- |
| `mic` | `AudioRecord` sur le micro (`MicCaptureThread`) | Entend la pièce, pas la musique : le moindre bruit ambiant passait pour un temps fort, et en Bluetooth le son de la voiture arrivait déformé et en retard. |
| `real` | Sortie de l'app suivie via `MediaProjection` (`AudioCaptureService`) | Le seul son juste, mais Android impose sa fenêtre « enregistrer l'écran » et ne se souvient d'aucune autorisation passée : à refaire à chaque lancement. |
| `off` | Rien | Une animation inventée. Elle répondait « oui » à « est-ce que ça réagit à la musique ? » sans rien écouter du tout. |

Trois options dont aucune n'était satisfaisante, et un choix à faire par quelqu'un qui n'a aucune
raison de connaître la différence.

## La décision

Une seule source : un `android.media.audiofx.Visualizer` attaché à la session audio de l'app de
musique elle-même (`TrackedSessionAudioSource`), publiée par `TrackedAudioCapture` dans
`AudioLevelsBridge`.

Elle ne demande **aucune fenêtre de consentement** — seulement `RECORD_AUDIO`, accordé une fois et
retenu par Android — et elle entend le morceau tel qu'il est joué, pas la pièce autour. Elle rend
les trois anciennes options inutiles : il n'y a plus rien à arbitrer, donc plus rien à choisir.

```
TrackedSessionAudioSource (Visualizer sur la session de Deezer)
        │
        ▼
   TrackedAudioCapture  ─────►  AudioLevelsBridge
                                      │
                        ┌─────────────┴─────────────┐
                        ▼                           ▼
              DeezerMediaPlugin              OverlayEdgeGlowService
             (lecteur plein écran)              (effet sur les bords)
```

Une seule capture alimente les deux. Passer de Vizuzik à Deezer ne démonte donc rien et ne
reconstruit rien : c'est la même session qui continue.

## Le nom de l'autorisation

Android appelle `RECORD_AUDIO` « microphone » dans sa fenêtre, et c'est la seule chose confuse dans
tout ceci. Rien n'ouvre jamais le micro : c'est simplement ce que `Visualizer` exige pour
s'attacher à une session audio. Impossible à contourner, donc autant l'écrire ici une fois pour
toutes.

## Le piège, et ce qu'il coûte

`ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION`, la diffusion qui annonce l'identifiant de session,
**n'est émise qu'une fois**, au moment où le lecteur ouvre sa session. Qui commence à écouter après
coup n'entend rien. `AudioSessionRegistry` écoute donc au niveau du processus, démarré depuis
`NowPlayingListenerService` — actif tant que l'accès aux notifications est accordé — et mémorise
l'identifiant ; les consommateurs le demandent quand ils démarrent.

Il reste une limite qu'aucune API ne permet de lever : si le processus démarre alors que la lecture
est **déjà** en cours, la diffusion est passée et rien ne permet de demander son identifiant de
session à une autre app. L'effet reste alors ambiant jusqu'au morceau suivant.

## Ce que ça supprime

- `MicCaptureThread.java`, `AudioCaptureService.java`
- Les méthodes plugin `startVisualizerCapture`, `stopVisualizerCapture`, `getCaptureState`,
  `startMicCapture`, `stopMicCapture`
- L'événement `micLevels`
- La permission `FOREGROUND_SERVICE_MEDIA_PROJECTION` et le service `mediaProjection`
- La fiche d'explication qui préparait à la fenêtre système, devenue sans objet

Le badge subsiste mais n'est plus un bouton : il dit ce que le visualiseur entend, il ne change
plus rien.

## Voir aussi

- [`requestAudioPermission` / `getAudioPermission`](../api/visualizer.md#lautorisation-audio)
- [Edge Visualizer](2026-09-06-edge-visualizer.md)
- Archivé : [le consentement de capture comme parcours](../legacy/2026-09-03-consentement-capture-audio.md),
  [activer le son réel](../legacy/capture-audio.md)
