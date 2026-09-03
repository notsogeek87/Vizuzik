# Les six modes de visualisation

**Pour qui :** utilisateurs de l'application, et toute personne qui veut comprendre ce que
montre chaque mode.

## En changer

Deux gestes équivalents, qui font défiler les modes en boucle :

- appuyer sur le bouton rond en haut à droite (son icône indique le mode courant) ;
- **taper** brièvement sur la pochette (un *glissement* horizontal, lui, change de morceau —
  voir [Gestes et contrôles](gestes.md)).

Une étiquette apparaît brièvement pour nommer le mode. Le choix est mémorisé
(`localStorage`, clé `vizuzik:displayMode`) et retrouvé au lancement suivant.

## Les modes

| Mode | Libellé | Ce qu'on voit |
|------|---------|---------------|
| `cover` | Pochette | La pochette en grand, entourée d'un halo discret et de rayons de spectre. L'artwork reste le sujet. |
| `bars` | Spectre | Spectre replié sur toute la largeur : graves au centre, aigus aux bords, avec reflet au sol et crêtes flottantes. |
| `radial` | Corona | Couronne de rayons autour du disque, étincelles en orbite et ondes de choc à chaque impulsion. |
| `aurora` | Aurore | Six rubans de lumière traversant l'écran, un par tranche de fréquence. |
| `nebula` | Nébuleuse | Galaxie de particules en orbite laissant des traînées lumineuses. |
| `cassette` | Cassette | Illustration fixe plein écran d'une cassette audio vue en portrait — l'écran du téléphone devient la fenêtre d'un baladeur. Rien n'y bouge ; seuls les moyeux des bobines suivent la palette de la pochette. |

Hors mode `cover`, la pochette se transforme en disque vinyle : elle rétrécit, s'arrondit,
tourne pendant la lecture et s'arrête à la pause. En mode `cassette`, elle disparaît
complètement derrière l'illustration plein écran.

## Ce qui réagit en dehors du canvas

- Le fond (pochette floutée + trois nuages de couleur) respire avec le volume.
- La lueur autour de la pochette et l'anneau du bouton lecture s'ouvrent sur chaque impulsion.
- Les couleurs de toute l'interface viennent de la pochette du morceau en cours.
- Un titre trop long défile lentement au lieu d'être coupé.

## Sans capture audio

Sans capture du son réel (voir [Activer le son réel](capture-audio.md)), l'application **ne
fait semblant d'aucun rythme**. Elle ne peut pas entendre la musique, et une pulsation inventée
tomberait forcément à côté de celle qu'on écoute — ce qui se remarque bien plus qu'une image
calme.

À la place, l'écran passe en **régime ambiant** :

- le disque tourne comme d'habitude, et la pochette reste le sujet ;
- les scènes coulent sur des vagues lentes, sans jamais frapper ;
- les couleurs **voyagent** : la scène glisse d'un accent de la pochette vers le suivant,
  environ une couleur toutes les 26 secondes, au lieu de clignoter ;
- l'écran ne s'illumine d'un coup que sur ce qui arrive vraiment : changement de morceau,
  lecture/pause, glissement pour changer de titre, changement de mode.

Le badge en haut à gauche indique toujours l'état réel, et reste le moyen d'activer la
capture.
