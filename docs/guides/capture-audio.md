# Activer le son réel

**Pour qui :** utilisateurs qui veulent que les visualisations suivent réellement la musique.

## Le principe : croiser la fenêtre système le moins possible

Écouter la sortie audio d'une autre application exige le consentement système Android
(`MediaProjection`) — la fenêtre « enregistrer ou caster l'écran ». Aucune application non
privilégiée ne peut s'en passer : la permission qui l'éviterait, `CAPTURE_AUDIO_OUTPUT`, est
réservée au système. Vizuzik ne peut donc pas la supprimer, seulement faire en sorte qu'on la
croise rarement, et qu'on sache à quoi elle sert quand elle arrive.

## Le parcours

0. **Avant tout ça :** si aucun titre n'est déjà affiché au lancement, Vizuzik ouvre Deezer lui
   -même (voir [Lancer Deezer soi-même](../architecture/2026-09-03-lancement-automatique-deezer.md)).
   Une fois de retour sur Vizuzik avec un morceau lancé, le parcours ci-dessous reprend
   normalement — y compris l'étape 4 si le consentement a déjà été donné par le passé.
1. **Premier lancement :** rien ne s'ouvre tout seul. Le badge en haut à gauche propose
   *« ▶ Activer le son réel »*.
2. **Au premier appui :** un écran d'explication apparaît d'abord — ce que Vizuzik fait du son,
   le fait que rien n'est enregistré ni envoyé, et le bouton à toucher dans la fenêtre Android
   qui suit (**Démarrer**). *« Plus tard »* referme sans rien demander au système.
3. **« Continuer » :** la fenêtre système s'ouvre. C'est le seul moment où on la voit.
4. **Lancements suivants :** l'accord est mémorisé. Dès qu'un morceau est à l'écran, Vizuzik
   redemande directement le consentement système — plus besoin de trouver le badge d'abord.
5. **En cas de refus :** le refus est mémorisé lui aussi. Plus rien ne s'ouvre spontanément ; le
   badge reste disponible si on change d'avis.

## Ce qui ne redéclenche plus la fenêtre

| Situation | Avant | Maintenant |
|---|---|---|
| Deezer se met en pause, ou un blanc entre deux titres | La capture était coupée, il fallait tout refaire | La capture continue |
| Retour dans l'app après un passage en arrière-plan | La fenêtre revenait | Rien à redemander |
| Webview rechargée alors que le service tourne | Le badge reproposait l'activation | L'état réel est relu côté natif |
| Appareil sans capture possible (Android 9 ou antérieur) | Badge « Réessayer (unsupported) » | Badge masqué |

La capture s'arrête pour de bon quand Vizuzik est retiré des applications récentes.

## Lire le badge

| Affichage | Signification |
|-----------|---------------|
| `▶ Activer le son réel` | Aucune capture : régime ambiant, sans rythme inventé (voir [Les modes](modes-de-visualisation.md#sans-capture-audio)). Appuyable. |
| `● Connexion…` | Demande en cours. |
| `● Son réel` | Les visualisations suivent l'audio de Deezer. |
| `● Son réel prêt` | Capture active, lecture en pause : rien à écouter pour l'instant. |
| `● Son réel (silencieux)` | La capture fonctionne mais renvoie du silence depuis plus de 3 s — en général Deezer a refusé la capture de lecture et Android renvoie un flux muet. |
| `● Son réel (signal faible)` | Capture active et lecture en cours, mais plus aucun niveau ne remonte. |
| `↻ Son réel (raison)` | Échec, avec la raison en clair : autorisation refusée, appareil non compatible, capture interrompue, ou absence de réponse du système. |

Le badge est masqué tant qu'aucun morceau n'est affiché, et sur les appareils incapables de
capturer.

## Ce que la capture change à l'écran

| | Sans capture | Avec capture |
|---|---|---|
| Rythme | Aucun n'est inventé : vagues lentes, couleurs qui voyagent | Le vrai : chaque kick est détecté |
| Impulsions | Changement de morceau, lecture/pause, glissement, changement de mode | Les mêmes, plus chaque kick |
| Disque, pochette, couleurs de l'interface | Identiques | Identiques |

## Prérequis

- Android 10 (API 29) ou supérieur.
- L'accès aux notifications doit déjà être accordé (écran d'autorisation au premier lancement).
- La capture est limitée à l'UID de Deezer ; les autres applications ne sont jamais écoutées.

## Repartir de zéro

L'accord (ou le refus) est stocké dans `localStorage` sous la clé `vizuzik:realAudio`
(`"on"` / `"off"`). L'effacer remet le parcours au premier lancement, écran d'explication
compris.
