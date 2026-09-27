# Polices embarquées

Deux polices manuscrites tierces, utilisées uniquement par les modes K7 pour écrire le titre et
l'artiste sur l'étiquette de la cassette. Elles ne font pas partie de Vizuzik : chacune garde sa
propre licence, distincte de la GPL-3.0 du reste du projet.

| Fichier | Police | Auteur | Licence | Utilisée par |
|---|---|---|---|---|
| `permanent-marker-latin-400.woff2` | Permanent Marker | Font Diner, Inc. (2010) | Apache 2.0 — [`LICENSE-permanent-marker.txt`](LICENSE-permanent-marker.txt) | K7 Étiquette |
| `reenie-beanie-latin-400.woff2` | Reenie Beanie | Typeco (2010) | SIL Open Font License 1.1 — [`LICENSE-reenie-beanie.txt`](LICENSE-reenie-beanie.txt) | K7 Classique |
| `android/app/src/main/res/font/permanent_marker.ttf` | Permanent Marker | Font Diner, Inc. (2010) | Apache 2.0 — même fichier de licence | K7 Étiquette, écran verrouillé |
| `android/app/src/main/res/font/reenie_beanie.ttf` | Reenie Beanie | Typeco (2010) | SIL Open Font License 1.1 — même fichier de licence | K7 Classique, écran verrouillé |

Provenance : Google Fonts, via les paquets npm Fontsource (`@fontsource/permanent-marker` et
`@fontsource/reenie-beanie`, version 5.3.0), sous-ensemble « latin » (alphabet latin avec accents,
dont é, è, à, ç, œ et €). Les fichiers ne sont pas modifiés : l'effet « gras léger » de K7
Classique est un contour ajouté à l'affichage (CSS), pas une retouche de la police.

Les deux copies `.ttf` servent au visualiseur de l'écran verrouillé, dessiné en natif (Android ne
lit pas le WOFF2). Ce sont les fichiers d'origine de Google Fonts, **complets et non modifiés**
(tous les jeux de caractères de la police, pas seulement le latin), récupérés via les paquets npm
`@expo-google-fonts/permanent-marker` 0.4.0 et `@expo-google-fonts/reenie-beanie` 0.4.1. Poids
ajouté à l'APK : environ 210 Ko (72 Ko + 137 Ko). Le dossier `res/font` d'Android n'accepte que
des polices : leurs licences sont celles de ce dossier-ci, qui se retrouve lui aussi dans l'APK.

## Ce que ces licences impliquent

Les deux licences sont compatibles avec la GPL-3.0 du projet et autorisent l'intégration et la
redistribution de ces polices dans l'application, y compris dans une version payante ou publiée
sur un store. En contrepartie :

- **Garder les fichiers de licence à côté des polices.** Ce dossier est copié tel quel dans l'APK
  (`public/` → `dist/` → assets Android), donc toute copie distribuée de l'app les contient. Ne
  pas les supprimer en déplaçant ou en renommant les polices.
- **Conserver les mentions de copyright** (en tête de chaque fichier de licence).
- **Ne pas vendre les polices seules**, séparées de l'application (OFL).
- **Ne pas publier une version modifiée d'une police sous son nom d'origine** (OFL pour Reenie
  Beanie ; Apache 2.0 demande de signaler toute modification). Tant que les fichiers restent tels
  quels, rien à faire.

Pour retirer une police, supprimer son `.woff2`, sa copie `.ttf` dans `res/font` (et son
chargement dans `EdgeGlowView.loadK7Fonts()`), son fichier de licence et sa règle `@font-face`
dans `src/style.css`.
