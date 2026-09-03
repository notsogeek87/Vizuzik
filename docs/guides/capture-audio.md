# Activer le son réel

**Pour qui :** utilisateurs qui veulent que les visualisations suivent réellement la musique.

## Pourquoi ce n'est pas automatique

Capturer la sortie audio d'une autre application demande le consentement système Android
(`MediaProjection`). Le déclencher automatiquement ferait surgir une fenêtre système à un
moment que l'utilisateur n'a pas demandé — par exemple en changeant simplement de style
visuel. L'activation est donc un geste explicite.

## Comment faire

Appuyer sur le badge en haut à gauche de l'écran lecteur, puis accepter la demande système.

## Lire le badge

| Affichage | Signification |
|-----------|---------------|
| `▶ Activer le son réel` | Aucune capture : animation synthétique. Appuyable. |
| `● Connexion…` | Demande en cours. |
| `● Son réel` | Les visualisations suivent l'audio de Deezer. |
| `● Son réel (silencieux)` | La capture fonctionne mais renvoie du silence depuis plus de 3 s — en général Deezer a refusé la capture de lecture et Android renvoie un flux muet. |
| `↻ Réessayer (raison)` | Échec, avec la raison réelle : refus, appareil non supporté, service arrêté, ou absence de réponse du système. |

## Prérequis

- Android 10 (API 29) ou supérieur.
- L'accès aux notifications doit déjà être accordé (écran d'autorisation au premier lancement).
- La capture est limitée à l'UID de Deezer ; les autres applications ne sont jamais écoutées.
