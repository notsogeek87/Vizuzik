# Les cinq modes de visualisation

**Pour qui :** utilisateurs de l'application, et toute personne qui veut comprendre ce que
montre chaque mode.

## En changer

Deux gestes équivalents, qui font défiler les modes en boucle :

- appuyer sur le bouton rond en haut à droite (son icône indique le mode courant) ;
- appuyer sur la pochette elle-même.

Une étiquette apparaît brièvement pour nommer le mode. Le choix est mémorisé
(`localStorage`, clé `vizuzik:displayMode`) et retrouvé au lancement suivant.

## Les modes

| Mode | Libellé | Ce qu'on voit |
|------|---------|---------------|
| `cover` | Pochette | La pochette en grand, entourée d'un halo discret et de rayons de spectre. L'artwork reste le sujet. |
| `bars` | Spectre | Spectre replié sur toute la largeur : graves au centre, aigus aux bords, avec reflet au sol et crêtes flottantes. |
| `radial` | Corona | Couronne de rayons autour du disque, étincelles en orbite et ondes de choc à chaque kick. |
| `aurora` | Aurore | Six rubans de lumière traversant l'écran, un par tranche de fréquence. |
| `nebula` | Nébuleuse | Galaxie de particules en orbite laissant des traînées lumineuses. |

Hors mode `cover`, la pochette se transforme en disque vinyle : elle rétrécit, s'arrondit,
tourne pendant la lecture et s'arrête à la pause.

## Ce qui réagit en dehors du canvas

- Le fond (pochette floutée + trois nuages de couleur) respire avec le volume.
- La lueur autour de la pochette et du bouton lecture pulse sur chaque kick.
- Les couleurs de toute l'interface viennent de la pochette du morceau en cours.
- Un titre trop long défile lentement au lieu d'être coupé.

## Sans capture audio

Si la capture du son réel n'est pas active (voir [Activer le son réel](capture-audio.md)),
les visualisations tournent sur un rythme synthétique : l'écran reste vivant, mais il ne suit
pas réellement la musique. Le badge en haut à gauche indique toujours l'état réel.
