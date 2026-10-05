# Mises à jour automatiques depuis les releases GitHub

**Pour qui :** développeurs qui touchent à `AppUpdater`, au workflow CI ou au versionnage.
**Pourquoi :** Vizuzik n'est pas sur le Play Store ; sans mécanisme intégré, chaque nouvelle
version exigeait de retélécharger l'APK à la main.

## Décision

L'app vérifie elle-même les releases du dépôt `notsogeek87/vizuzik` via la bibliothèque
`com.lielu:lielugit-updater:1.0.0`, **vendorée** dans `android/libs/lielugit-maven` (dépôt Maven
local, aucun jeton ni secret ; exception dans `android/.gitignore` pour son `.aar`).

- **`AppUpdater`** (singleton Kotlin) : enveloppe `UpdateManager`, partage l'état entre la fenêtre
  de lancement et le bouton des réglages.
- **`UpdateDialogs`** : fenêtre native qui guide l'installation (téléchargement, autorisation
  « sources inconnues », confirmation Android).
- **`AppUpdatePlugin`** (Capacitor, `AppUpdate`) : pont du bouton « Rechercher une mise à jour »
  des réglages ; renvoie au web un statut `upToDate | available | busy | disabled | error`.

### Quand la vérification a lieu

`MainActivity.onStart` appelle `AppUpdater.checkOnOpen()` à **chaque passage au premier plan**,
avec `checkForUpdate(force = true)` : sans `force`, la bibliothèque réutilise sa réponse
« à jour » pendant `checkIntervalHours` et une release publiée entre-temps passerait inaperçue.
Coût : une requête GitHub par ouverture, très en dessous du quota anonyme (60/h).

- « Plus tard » ferme la fenêtre jusqu'à la prochaine ouverture.
- Les erreurs (hors ligne, quota) restent silencieuses tant que l'utilisateur n'a rien lancé ;
  elles ne sont montrées qu'après « Installer » ou « Rechercher » (`userStarted`).
- Un téléchargement ou une installation en cours n'est jamais écrasé.
- Un `applicationId` à suffixe (`.staging`) désactive tout : il ne peut pas être mis à jour
  par la release de production (`CheckResult.Disabled`).

## Versionnage (contrainte forte)

Android refuse une mise à jour dont le `versionCode` n'augmente pas, et la bibliothèque compare
les tags comme des versions. Donc, dans `android/app/build.gradle` :

| Champ | Valeur |
|---|---|
| `versionCode` | `BUILD_NUMBER` (= `github.run_number` en CI, `1` en local) |
| `versionName` | `<appVersionBase>.<BUILD_NUMBER>`, `appVersionBase` dans `android/gradle.properties` |
| Tag de release | `v<versionName>` (ex. `v1.0.152`) |

Un tag de forme `v1.0-25` serait lu comme une pré-version `< 1.0` et ne déclencherait **jamais**
de mise à jour : le tag doit rester `v<base>.<run>`.

## Conséquences

- L'APK est signé avec la clé debug committée, identique pour tous les builds : c'est ce qui
  permet à Android d'accepter l'installation par-dessus la version précédente (réglages conservés).
  Passer à une clé de production casserait la chaîne de mises à jour pour les installations
  existantes.
- Le texte de chaque release (généré par `.github/workflows/android.yml`) contient les
  instructions d'installation.
- Kotlin est désormais requis dans le module `app` (JVM 21).

Voir le parcours côté utilisateur : [Mises à jour de l'app](../guides/mises-a-jour.md).
