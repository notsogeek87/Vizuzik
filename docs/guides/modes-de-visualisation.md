# Les sept modes de visualisation

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
| `cocoon` | Cocon | Un ruban de lumière tressé (trois brins) s'enroule tout contre la pochette, qui reste carrée comme en mode `cover` — pas de disque vinyle ici. Des étincelles orbitent juste à l'extérieur du ruban. |
| `cassette` | Cassette | Illustration plein écran d'une cassette audio — l'écran du téléphone devient la fenêtre d'un baladeur. Le boîtier ne bouge pas, mais les deux bobines (bande enroulée, dents du moyeu) tournent pendant la lecture et s'arrêtent à la pause ; les moyeux et la plaque de marque suivent la palette de la pochette, et la pochette du morceau en cours est imprimée sur l'étiquette. Ce mode force l'écran en paysage (voir plus bas) ; si le téléphone reste en portrait le temps que la rotation se fasse, l'illustration pivote pour rester plein écran. **Taper** l'écran replie les boutons de lecture, la barre de progression, la carte titre/artiste et la barre du haut (badge d'état audio + bouton de mode) pour une vue totalement dégagée de la cassette ; un second tap les ramène. Le titre/artiste (près du haut, sur une carte translucide) reste volontairement à l'écart des bobines. |
| `k7-etiquette` | K7 Étiquette | Une autre cassette, dessinée d'après une vraie cassette compacte plutôt que d'après la fenêtre d'un baladeur : une grande étiquette couvre presque toute la face, les deux moyeux blancs se voient par une fenêtre ovale au centre, et la partie basse trapézoïdale (vis, trous des cabestans) est représentée. La pochette est imprimée sur toute l'étiquette, sous une bande translucide où le titre et l'artiste sont écrits au marqueur noir (police Permanent Marker), réduits puis coupés par « … » s'ils sont trop longs. La coque est teintée par la couleur principale de la pochette, comme une cassette préenregistrée en plastique coloré. Les moyeux, avec leurs flasques transparentes à rayons, tournent pendant la lecture dans le sens inverse des aiguilles d'une montre, comme sur une vraie platine ; la bande enroulée, visible par la fenêtre, passe de la bobine gauche à la droite au fil du morceau. À chaque nouveau morceau, la cassette se rembobine : les moyeux tournent vite dans l'autre sens pendant que la bande revient sur la bobine gauche. Un déplacement dans la barre de progression fait glisser les bobinages plutôt que de les faire sauter. Illustration pivotée en portrait, comme `cassette`. **Les contrôles sont sur le capot d'un baladeur** : au lancement du mode, rien ne recouvre la cassette ; **taper** l'écran referme sur elle un capot en aluminium brossé, avec une vitre sur les bobines, trois touches (précédent, lecture/pause — enfoncée pendant la lecture — et suivant), un petit écran LCD pour le temps et une longue réglette graduée dont l'aiguille rouge se fait **glisser au doigt** pour se déplacer dans le morceau ; un nouveau tap hors des touches rouvre le capot. Les reflets suivent l'inclinaison du téléphone : une bande de lumière sur le métal, et les reflets de la vitre, qui se déplacent un peu moins, comme sur un second plan. Ni les contrôles habituels de l'app, ni la carte titre/artiste, ni le voile sombre du bas ne s'affichent dans ce mode. |
| `k7-classique` | K7 Classique | La même cassette, avec une étiquette « à l'ancienne » : fond gris-bleu teinté par la couleur principale de la pochette, bande de papier réglée avec titre et artiste écrits au crayon (police Reenie Beanie, légèrement épaissie), deux rayures et une pastille aux couleurs de la pochette, et la pochette en vignette à gauche au-dessus du nom VIZUZIK. La coque est celle d'une cassette vierge enregistrée soi-même : plastique fumé transparent, à travers lequel on voit la mécanique (bobinage plein qui dépasse de l'étiquette, bande qui part du bord extérieur de chaque bobinage, galets de guidage dans les coins, patin presseur, stries moulées), avec un halo aux couleurs de la pochette derrière la cassette. |

Les deux modes K7 existent aussi comme styles de l'**écran verrouillé** (Réglages → « Style de l'écran verrouillé ») : la même cassette dessinée en natif, bobines, rembobinage, étiquette et écriture manuscrite comprises, mais sans le capot (l'écran verrouillé garde ses trois boutons, posés sur la partie basse de la cassette — en colonne à gauche quand le téléphone est tenu droit, icônes tournées comme la cassette), sans les reflets liés à l'inclinaison et sans le halo. Voir [Visualiseur écran verrouillé](../architecture/2026-09-09-visualiseur-ecran-verrouille.md).

Hors modes `cover` et `cocoon`, la pochette se transforme en disque vinyle : elle rétrécit,
s'arrondit, tourne pendant la lecture et s'arrête à la pause. En mode `cocoon`, elle reste
carrée et immobile, comme en mode `cover` — c'est le ruban tressé autour d'elle qui bouge. En
mode `cassette`, elle disparaît complètement derrière l'illustration plein écran.

## Cassette et paysage

En entrant en mode `cassette`, l'app demande à Android de forcer l'écran en paysage
(`DeezerMedia.lockLandscape()`, natif — voir `docs/api/visualizer.md`) : l'auto-rotation du
téléphone bascule alors l'écran d'elle-même, sans que l'utilisateur ait besoin de désactiver le
verrouillage de rotation du système. Une orientation paysage **fixe** (pas `SENSOR_LANDSCAPE`,
qui accepte aussi le paysage inversé) : ce dernier faisait apparaître le glissement
suivant/précédent inversé sur ce WebView. En quittant ce mode, l'orientation redevient libre
(`unlockOrientation()`). Les boutons de lecture se replient en bas de l'écran une fois en
paysage plutôt que de rester centrés verticalement, pour profiter d'un écran large et bas.

## Ce qui réagit en dehors du canvas

- Le fond (pochette floutée + trois nuages de couleur) respire avec le volume.
- La lueur autour de la pochette et l'anneau du bouton lecture s'ouvrent sur chaque impulsion.
- Les couleurs de toute l'interface viennent de la pochette du morceau en cours.
- Un titre trop long défile lentement au lieu d'être coupé.

## Quand l'application n'entend rien

Quand rien n'arrive de la capture (autorisation refusée, ou aucune session audio à suivre —
voir [Une seule source audio](../architecture/2026-09-07-source-audio-unique.md)), l'application
**ne fait semblant d'aucun rythme**. Elle ne peut pas entendre la musique, et une pulsation inventée
tomberait forcément à côté de celle qu'on écoute — ce qui se remarque bien plus qu'une image
calme.

À la place, l'écran passe en **régime ambiant** :

- le disque tourne comme d'habitude, et la pochette reste le sujet ;
- les scènes coulent sur des vagues lentes, sans jamais frapper ;
- les couleurs **voyagent** : la scène glisse d'un accent de la pochette vers le suivant,
  environ une couleur toutes les 26 secondes, au lieu de clignoter ;
- l'écran ne s'illumine d'un coup que sur ce qui arrive vraiment : changement de morceau,
  lecture/pause, glissement pour changer de titre, changement de mode.

Le badge en haut à gauche dit toujours ce que le visualiseur entend réellement. Ce n'est
qu'un indicateur : il n'y a plus de source à choisir.
