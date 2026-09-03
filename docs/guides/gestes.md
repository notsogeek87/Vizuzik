# Gestes et contrôles

**Pour qui :** utilisateurs de l'application.

## Barre de progression

Sous le titre : le temps écoulé à gauche, la durée totale à droite, et une barre remplie aux
couleurs de la pochette dont la lueur pulse sur les basses.

- **Appuyer** n'importe où sur le bloc de la barre (minutages compris) saute à cet endroit du
  morceau ; **glisser** déplace la lecture en continu.
- La barre **disparaît** quand Deezer n'annonce pas de durée (flux en direct, par exemple) ;
  la place reste réservée pour que les contrôles ne sautent pas d'un morceau à l'autre.
- Si Deezer refuse la recherche, la barre revient à la position réelle dans les cinq secondes.

Entre deux mises à jour de Deezer, la barre avance sur une horloge locale, resynchronisée
toutes les cinq secondes sur la position réelle.

## Gestes

| Geste | Effet |
|---|---|
| **Taper** sur la pochette | Mode de visualisation suivant |
| **Glisser vers la gauche** | Morceau suivant |
| **Glisser vers la droite** | Morceau précédent |
| Bouton rond en haut à droite | Mode de visualisation suivant |
| Badge en haut à gauche | Activer la capture du son réel |

Le glissement doit dépasser environ 64 px horizontaux pour déclencher le changement ; en
dessous, la pochette revient en place. Pendant le geste elle suit le doigt avec un léger retard
et s'estompe, ce qui rend le seuil perceptible avant de relâcher. Une fois le seuil franchi,
elle sort de l'écran puis revient de l'autre côté, comme un carrousel.

Les boutons de lecture et la barre de progression gardent leurs propres gestes : un glissement
qui commence sur eux ne change pas de morceau.
