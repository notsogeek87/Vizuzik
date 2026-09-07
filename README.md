# Vizuzik

Application Android plein écran affichant la lecture en cours sur Deezer (pochette, titre,
artiste) avec les contrôles précédent / lecture-pause / suivant, et un moteur visuel qui
réagit à la musique.

L'app lit la session multimédia de Deezer via l'API Android de notification listener : au
premier lancement, elle demande d'autoriser l'accès aux notifications (Réglages > Accès aux
notifications) pour pouvoir afficher et contrôler la lecture.

## Le visuel

Sept modes, en boucle via le bouton en haut à droite ou en touchant la pochette : **Pochette**,
**Spectre**, **Corona**, **Aurore**, **Nébuleuse**, **Cocon**, **Cassette**. Les couleurs de
toute l'interface sont extraites de la pochette du morceau en cours, et la lueur pulse sur les
basses.

Sans capture du son, l'application n'invente aucun rythme : le disque tourne, les scènes
coulent sur des vagues lentes, les couleurs voyagent d'un accent de la pochette au suivant, et
l'écran ne s'illumine que sur un évènement réel (changement de morceau, lecture/pause,
glissement). Pour que les visualisations suivent réellement le son, appuyer sur le badge en
haut à gauche — voir [Activer le son réel](docs/guides/capture-audio.md).

## Edge Visualizer

Un second badge, à côté de celui du son réel, allume un contour lumineux dessiné **par-dessus
l'app de musique elle-même** (Deezer, Spotify, YouTube Music, un lecteur local...) — façon MuViz
Edge — dès qu'un morceau joue et que Vizuzik n'est pas à l'écran. Purement décoratif (aucun geste
n'est jamais capté), il s'éteint tout seul dès qu'on revient sur Vizuzik ou que la lecture
s'arrête. L'icône réglages à côté du badge ouvre le panneau : style, fréquences utilisées,
intensité, épaisseur, luminosité, sensibilité, couleurs (auto depuis la pochette ou fixes), et
l'activation de chacun des quatre bords. Voir [Edge Visualizer, sans micro
cette fois](docs/architecture/2026-09-06-edge-visualizer.md).

## Contrôles

Barre de progression avec temps écoulé, durée et déplacement dans le morceau. Sur la pochette :
**taper** change de visualisation, **glisser à gauche ou à droite** change de morceau — voir
[Gestes et contrôles](docs/guides/gestes.md).

## Développement

```bash
npm install
npm run dev          # serveur Vite
npm run build        # build web
npm run cap:sync     # build + synchronisation Capacitor vers android/
npm run android:open # ouvre le projet Android Studio
```

## Documentation

Voir [`docs/`](docs/README.md) : décisions d'architecture, API interne (`Visualizer`,
`extractPalette`) et guides d'utilisation.
