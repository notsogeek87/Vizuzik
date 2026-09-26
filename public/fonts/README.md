# Polices embarquées

Deux polices manuscrites tierces, utilisées uniquement par les modes K7 pour écrire le titre et
l'artiste sur l'étiquette de la cassette. Elles ne font pas partie de Vizuzik : chacune garde sa
propre licence, distincte de la GPL-3.0 du reste du projet.

| Fichier | Police | Auteur | Licence | Utilisée par |
|---|---|---|---|---|
| `permanent-marker-latin-400.woff2` | Permanent Marker | Font Diner, Inc. (2010) | Apache 2.0 — [`LICENSE-permanent-marker.txt`](LICENSE-permanent-marker.txt) | K7 Étiquette |
| `reenie-beanie-latin-400.woff2` | Reenie Beanie | Typeco (2010) | SIL Open Font License 1.1 — [`LICENSE-reenie-beanie.txt`](LICENSE-reenie-beanie.txt) | K7 Classique |

Provenance : Google Fonts, via les paquets npm Fontsource (`@fontsource/permanent-marker` et
`@fontsource/reenie-beanie`, version 5.3.0), sous-ensemble « latin » (alphabet latin avec accents,
dont é, è, à, ç, œ et €). Les fichiers ne sont pas modifiés : l'effet « gras léger » de K7
Classique est un contour ajouté à l'affichage (CSS), pas une retouche de la police.

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

Pour retirer une police, supprimer son `.woff2`, son fichier de licence et sa règle `@font-face`
dans `src/style.css`.
