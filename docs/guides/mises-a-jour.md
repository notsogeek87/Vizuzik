# Mises à jour de l'app

**Pour qui :** utilisateurs de Vizuzik.

## Mise à jour automatique

À chaque ouverture, Vizuzik cherche une version plus récente sur GitHub. Si elle existe, une
fenêtre s'affiche :

1. Touchez **Installer** (ou **Plus tard** : la fenêtre reviendra à la prochaine ouverture).
2. Si Android le demande, autorisez Vizuzik à installer des applications inconnues, puis revenez
   dans l'app.
3. Confirmez **Mettre à jour** dans la fenêtre d'Android.

Vos réglages sont conservés.

## Vérifier à la demande

Réglages (engrenage) → **Rechercher une mise à jour**. Le résultat s'affiche sous le bouton :
à jour, mise à jour disponible, opération déjà en cours, ou message d'erreur (hors ligne, quota
GitHub atteint).

## Première installation

Téléchargez `Vizuzik-<version>.apk` depuis la
[page des releases](https://github.com/notsogeek87/vizuzik/releases), ouvrez-le, autorisez la
source si Android bloque (Réglages → Installer des applications inconnues), puis **Installer**.
Les versions suivantes seront proposées dans l'app.

## Limites

- Les builds dont l'identifiant d'application se termine par `.staging` ne se mettent pas à jour
  depuis la release de production.
- Hors ligne, aucune erreur n'apparaît à l'ouverture : elle n'est montrée que si vous touchez
  « Rechercher une mise à jour ».

Détails techniques : [Mises à jour automatiques](../architecture/2026-10-04-mises-a-jour-automatiques.md).
