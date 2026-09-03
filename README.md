# Vizuzik

Application Android plein écran affichant la lecture en cours sur Deezer (pochette, titre,
artiste) avec les contrôles précédent / lecture-pause / suivant, et un moteur visuel qui
réagit à la musique.

L'app lit la session multimédia de Deezer via l'API Android de notification listener : au
premier lancement, elle demande d'autoriser l'accès aux notifications (Réglages > Accès aux
notifications) pour pouvoir afficher et contrôler la lecture.

## Le visuel

Cinq modes, en boucle via le bouton en haut à droite ou en touchant la pochette : **Pochette**,
**Spectre**, **Corona**, **Aurore**, **Nébuleuse**. Les couleurs de toute l'interface sont
extraites de la pochette du morceau en cours, et la lueur pulse sur les basses.

Pour que les visualisations suivent réellement le son plutôt qu'un rythme simulé, appuyer sur
le badge en haut à gauche — voir [Activer le son réel](docs/guides/capture-audio.md).

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
