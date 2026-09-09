# Visualiseur écran verrouillé : la meilleure imitation d'une AOD accessible à une app tierce

**Statut :** adopté · **Date :** 2026-09-09 · **Concerne :**
`LockScreenVisualizerActivity.java`, `LockScreenVisualizerController.java`,
`LockScreenVisualizerPreference.java`, `EdgeGlowView.java` (mode `standalone`), `EdgeConfig.java`,
`NowPlayingListenerService.java`, `DeezerMediaPlugin.java`, `AndroidManifest.xml`, `index.html`,
`src/main.js`

## La question posée

Muviz Edge propose plusieurs styles de visualisation « sur l'AOD ». Avant d'écrire une ligne de
code : est-ce que Vizuzik peut faire pareil, et comment Muviz Edge s'y prend-il réellement ?

## Ce qui a été vérifié, pas supposé

**Il n'existe aucune API publique, sur Android ou sur One UI, permettant à une application tierce
de dessiner un contenu personnalisé sur la vraie AOD matérielle** — l'écran toujours allumé, en
veille, piloté par un composant système (`SystemUI`/le service d'ambiance sur AOSP ; côté Samsung,
un module interne lié aux thèmes Galaxy et à « Good Lock ») qui tourne dans un mode d'affichage
basse consommation dédié, indépendant du reste du système. Ça vaut pour toutes les versions
d'Android et One UI, y compris sur le Z Fold8 ciblé ici — ce n'est pas une limitation qu'une
version future lèvera, c'est une frontière du système fermée à toute app tierce par conception.

Ce que Muviz Edge appelle « AOD » n'est donc **pas** un rendu sur la vraie AOD. C'est un troisième
mécanisme, différent des deux que Vizuzik avait déjà (le rendu plein écran de l'app, et l'Edge
Visualizer par-dessus une autre app pendant que l'écran est allumé — voir
[Edge Visualizer](2026-09-06-edge-visualizer.md)) :

**Une Activity plein écran affichée par-dessus l'écran de verrouillage, l'écran étant
explicitement gardé allumé.**

C'est de l'API Android publique et documentée — le même mécanisme qu'un appel entrant ou un
réveil utilisent pour s'afficher par-dessus le verrouillage sans le désactiver
(`Activity.setShowWhenLocked()` + `setTurnScreenOn()`, déclenché depuis l'arrière-plan via une
notification à intention plein écran). Ni plus, ni moins : ça ne contourne aucune protection, mais
ça ne touche pas non plus à la vraie AOD — le panneau reste piloté normalement par le logiciel de
l'app, pas par le contrôleur d'affichage bas niveau.

### Ne pas confondre les six mécanismes

| Mécanisme | Ce que c'est | Ce que Vizuzik en fait |
|---|---|---|
| AOD Samsung native | Écran de veille basse consommation, piloté par le système, aucune API tierce | Rien — impossible, voir plus haut |
| Écran de verrouillage | Le keyguard système (code, biométrie) | Jamais désactivé ni contourné par ce qui suit |
| Overlay au-dessus de l'écran | Une fenêtre `TYPE_APPLICATION_OVERLAY` par-dessus une autre app, écran allumé | Edge Visualizer (existant) |
| Notification média | Les contrôles de lecture dans le tiroir de notifications/sur l'écran verrouillé | Utilisé pour lire l'état de lecture (`NowPlayingListenerService`), jamais pour du rendu |
| « Visualiseur Edge » | Le nom donné ici à l'overlay ci-dessus | Idem |
| Ce nouveau « visualiseur écran verrouillé » | Une Activity plein écran par-dessus le keyguard, écran gardé allumé | **Ce document** |

## Le prix à payer, précisément

- **Batterie.** L'écran reste réellement piloté en plein régime — pas dans le chemin de
  rafraîchissement ultra basse consommation dédié de la vraie AOD. Sur de l'OLED avec un fond
  presque noir, la différence *visuelle* est faible ; la différence de *consommation* ne l'est
  pas. C'est exactement le compromis que les apps « AOD » du Play Store assument déjà, et la
  plainte n°1 qui leur est faite.
- **Permissions.** `USE_FULL_SCREEN_INTENT` est pensée par Android pour les appels et les
  réveils ; Vizuzik n'est ni l'un ni l'autre. Depuis Android 14, l'utilisateur doit accorder cet
  accès séparément dans un écran système dédié (`Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT`)
  — la déclarer dans le manifeste ne suffit plus. `POST_NOTIFICATIONS` (Android 13+) est également
  nécessaire : sans elle, la notification qui porte l'intention plein écran n'est même pas postée.
- **Robustesse du déclenchement.** Il n'existe pas de diffusion « l'écran va s'éteindre » — seulement
  `ACTION_SCREEN_OFF`, une diffusion implicite qu'Android ne livre plus aux récepteurs déclarés
  dans le manifeste depuis des années ; elle doit être enregistrée à l'exécution, depuis un
  composant garanti vivant (voir plus bas). Faire réapparaître une Activity plein écran depuis un
  processus sans rien au premier plan est par ailleurs restreint depuis Android 10 — la seule voie
  encore sanctionnée est une notification à haute importance portant une intention plein écran,
  exactement ce qu'utilisent un appel ou un réveil.

Rien de tout ça n'a pu être testé sur un appareil réel dans cette session — voir la section Tests.

## L'architecture retenue

### `LockScreenVisualizerController` (natif, singleton)

Le pendant natif d'`EdgeOverlayController`, pour la même raison : la fonctionnalité doit marcher
même si l'Activity/webview de Vizuzik n'a jamais tourné cette session (l'app suivie déjà en train
de jouer, ou l'utilisateur ouvrant directement Deezer). Enregistré comme écouteur de
`DeezerMediaBridge` et comme récepteur de `ACTION_SCREEN_OFF` (à l'exécution,
`RECEIVER_NOT_EXPORTED` puisque c'est une diffusion protégée que seul le système peut envoyer)
depuis `NowPlayingListenerService.onListenerConnected()` — le même composant garanti vivant que
`EdgeOverlayController`.

La décision (`maybeShow()`) ne mémorise rien de son cru : `PowerManager.isInteractive()` est
interrogé à chaque fois plutôt que suivi dans un booléen séparé qui pourrait diverger — une fois
l'Activity affichée, l'écran redevient « interactif » (c'est `setTurnScreenOn()` qui le fait), et
la question ne se pose donc plus tant qu'elle reste affichée. Une seule source de vérité au lieu
de deux à garder synchronisées.

### `LockScreenVisualizerActivity`

Héberge la même `EdgeGlowView` que l'overlay, en mode `standalone` (nouveau, minimal, additif —
voir plus bas) : aucune autre app en dessous à protéger ni à masquer pour, et aucune mise en page
Deezer contre laquelle ancrer « Cocon »/« Vinyle », puisque cette Activity est déjà tout l'écran.
Alimentée directement par les deux mêmes ponts qu'`OverlayEdgeGlowService` écoute
(`DeezerMediaBridge`, `AudioLevelsBridge`) — jamais une seconde capture de l'un ou de l'autre.

Ne dissout jamais elle-même le verrouillage : `KeyguardManager.requestDismissKeyguard()` n'est
jamais appelée (une version précédente de ce fichier, écrite puis corrigée avant tout commit, s'y
essayait par erreur — cette méthode déverrouille, ce qui est l'exact contraire du but). Seuls un
double tap ou le geste/bouton système d'accueil font retomber l'écran sur ce qu'il y aurait
normalement — l'écran de verrouillage réel, tel quel (voir le correctif ci-dessous : un appui
simple ou la touche retour ne le font plus).

**Correctif : un simple appui, déclenché par le mouvement du téléphone, dissolvait l'écran vers le
vrai verrouillage.** Premier retour terrain : le téléphone bougeant en poche ou en sac (tissu
frottant l'écran) déclenchait des appuis accidentels sur cette Activity plein écran, qui
`finish()`ait au premier appui — retombant sur le vrai `keyguard`, qui tentait alors de se
déverrouiller sans que l'utilisateur l'ait demandé. La touche retour posait le même risque (un
geste de bord accidentel pendant le mouvement). Correctif : un `GestureDetector` remplace le
`OnClickListener` — seul un double tap appelle désormais `finish()` — et un
`OnBackPressedCallback` avale la touche retour au lieu de la laisser dissoudre l'écran par défaut.
Le geste/bouton système d'accueil continue de fonctionner sans changement : il sort déjà cette
Activity du premier plan, ce qui déclenche `onStop()` → `finish()`.

S'arrête d'elle-même (`onStop()` → `finish()`) dès qu'autre chose passe au premier plan — un
déverrouillage réel, le bouncer du keyguard, un appel — puisqu'il n'y a rien à reprendre ensuite.
S'arrête aussi si la lecture s'arrête (avec la même grâce de 1,5 s qu'`EdgeOverlayController` pour
ne pas réagir à un simple changement de piste), et si le réglage est désactivé pendant qu'elle est
affichée (`LockScreenVisualizerController.onDisabled()` envoie une diffusion interne à l'app que
cette Activity écoute).

### `EdgeGlowView` : mode `standalone`, additif

Plutôt que dupliquer le moteur de rendu (barres/contour/particules/cocon/vinyle, dégradés,
palette, easing des niveaux…) dans une deuxième classe, `EdgeGlowView` gagne un simple booléen
`standalone` :

- `updateSuppression()` ne fait plus rien (toujours visible) : il n'y a pas d'« autre app » dont se
  cacher ni à protéger des touches.
- `activeStyle()` retombe toujours sur le style de repli pour « Cocon » : il n'y a pas de mise en
  page Deezer à modéliser sur un fond qui est le sien.
- Les diagnostics de l'overlay (`OverlayDiagnostics`, statiques et partagés) ne sont pas publiés
  depuis une instance `standalone`, pour ne pas se mélanger avec ceux de l'overlay.

Aucune des méthodes de rendu existantes (`drawBars`, `drawGlow`, `drawParticles`, `drawCocoon`,
`drawVinyl`) n'a été touchée : tous les styles ajoutés à Edge Visualizer restent disponibles ici
sans travail supplémentaire, dès qu'un nouveau en arrive.

**Mise à jour :** « Vinyle » et « Cassette » (nouveau, natif — voir plus bas) ne suivent plus cette
règle de repli. `artRect()` gagne sa propre branche `standalone` : plutôt que le modèle de mise en
page Deezer (les constantes `ART_*`), elle centre l'ancre sur l'écran lui-même, à une fraction fixe
de son côté le plus court (`STANDALONE_ART_FRACTION`, 62 % — voir plus bas pour le compromis
batterie). « Cocon » continue de retomber sur le style de repli : rien n'a changé pour lui, voir
« Pistes non retenues ».

**Deuxième mise à jour :** « Cassette » ne partage plus cette branche `standalone` d'`artRect()`
avec « Vinyle ». Voir la section dédiée plus bas : elle occupe désormais tout l'écran, comme sur le
lecteur web, plutôt qu'une icône centrée à 62 %.

### Le style « Cassette », natif et propre à cet écran

Un sixième style dans `EdgeGlowView` (`EdgeConfig.STYLE_CASSETTE`, `drawCassette()`), jamais
proposé dans le sélecteur « Style » d'Edge Visualizer : contrairement aux cinq autres, il n'a rien
à écouter par-dessus une autre app (pas de bordure à border, pas de pochette Deezer à redessiner),
donc rien à y faire. Choisi à la place depuis le sélecteur propre à cet écran — trois options
seulement (Barres/Cassette/Disque), stockées dans `LockScreenVisualizerPreference` plutôt que dans
`EdgeConfig`, lues une seule fois par `LockScreenVisualizerActivity.onStart()` (`setStandaloneStyle()`
côté `EdgeGlowView`, jamais piloté par `applyConfig()`) — les deux réglages sont volontairement
indépendants, pas de partage d'un « style » unique entre l'overlay et cet écran.

Un portage coordonnée par coordonnée de l'illustration `.cassette` du lecteur web
(`index.html`/`style.css`, viewBox `320x200`), volontairement plus simple qu'elle : pas de pochette
sur l'étiquette (rien de la trempe d'une photo à afficher sur un écran qui reste allumé en continu),
ni le balayage de reflet ni le grain plastique — purement décoratifs, et cette vue se redessine
jusqu'à 30 fois par seconde tant l'écran verrouillé est affiché. La seule partie qui bouge encore
est celle qui justifie ce coût : les deux bobines, tournant à deux vitesses légèrement différentes
(`CASSETTE_DEG_PER_SEC_A`/`_B`, les mêmes que `.cassette__reel`/`.cassette__reel--b` côté web),
seulement pendant la lecture — exactement la même règle que la rotation de « Vinyle ».

**Plein écran, pas une icône centrée.** Contrairement à « Vinyle », `drawCassette()` ne passe pas
par la branche `standalone` d'`artRect()` : elle calcule sa propre mise à l'échelle en « cover »
(recadrée, jamais en lettrebox) directement à partir de la taille de l'écran, pour occuper tout
l'écran bord à bord — exactement le rendu du lecteur web (`.cassette__art`, `width:100%;
height:100%`, viewBox en `xMidYMid slice`), plutôt que le traitement partagé avec « Vinyle » qui la
réduisait à une icône centrée sur 62 % du plus petit côté. En paysage, l'illustration (dessinée
« côté cassette », donc paysage) n'a besoin de rien de plus. En portrait — l'orientation normale
d'un écran verrouillé — elle est tournée de 90° autour du centre de l'écran et son échelle de
recouvrement est mesurée contre la boîte permutée (la hauteur d'écran comme largeur, la largeur
d'écran comme hauteur), pour que l'illustration tournée continue de courir bord à bord sans
lettrebox : la même astuce que la règle web
`@media (orientation: portrait) { .cassette__art { width: 100vh; height: 100vw; transform:
translate(-50%, -50%) rotate(90deg); } }`.

**Une deuxième relecture, du grain en plus : trop plat.** Le premier jet — plein écran ou non —
rendait à plat, remonté à la fois côté web et côté natif. Portées ici sous forme de
`RadialGradient` (`buildCassetteShaders()`) les mêmes trois idées que côté web (voir
`docs/architecture` → le CSS lui-même, ou directement `index.html`/`style.css`) : un spot et un
vignettage sur la coque, une ombre portée par bobine sur le fond de la fenêtre, et un dégradé
radial sur le disque de chaque bobine plutôt que des anneaux sur un fond plat. Statiques :
construites une seule fois, au premier appel de `drawCassette()`, jamais reconstruites ensuite —
contrairement à `buildVinylShaders()`, rien ici ne dépend de la taille réelle de l'écran (tout est
déjà dans l'espace du viewBox 320x200 fixe une fois le canvas mis à l'échelle, y compris la mise à
l'échelle « cover » ajoutée juste au-dessus), donc rien ne peut jamais avoir besoin d'être
régénéré.

**Une troisième relecture : plus proche d'une vraie cassette, pas juste moins plate.** Demandé
explicitement plus « réaliste », en repartant d'une photo de référence (cassette « SIDE B », bande
arc-en-ciel, bobines claires). Trois changements, côté web et natif à la fois :

- **Bobines claires plutôt que sombres.** `cassette-reel-disc`/`cassetteReelDiscShader` passe d'un
  dégradé gris-noir à un dégradé quasi blanc (`#eef0f6 → #b7bac6 → #54545e`) — les bobines réelles
  sont moulées dans un polystyrène translucide clair, pas dans du plastique sombre ; c'était le
  détail qui faisait le plus « illustration » plutôt que « cassette ».
- **Bande de bobine liée à la lecture réelle.** Chaque bobine avait deux fins anneaux fixes
  identiques en guise de bande enroulée — remplacés par un seul disque plein (`.cassette__coil`
  côté web, un simple `drawCircle` de rayon variable côté natif) dont le rayon suit `--progress`
  (0..1, écrit dans `onFrame` de `main.js` depuis la même instance `PlaybackProgress` que la barre
  de lecture) côté web, et `cassetteProgress()` (ancré sur `positionMs`/`durationMs` de
  `DeezerMediaBridge.NowPlaying`, extrapolé par le temps réellement écoulé, exactement comme
  `PlaybackProgress.positionNow()`) côté natif. La bobine gauche (« alimentation ») commence pleine
  et se réduit vers son moyeu à mesure que le morceau avance ; la droite (« réception ») fait
  l'inverse — le sens réel de déroulement d'une cassette, pas une asymétrie arbitraire.
- **Détails typographiques/mécaniques** repris de la photo de référence : la pastille d'accent
  devient une bande à trois couleurs (les trois accents de la pochette plutôt que les deux du
  dégradé `#cassette-accent`), chaque moyeu de bobine gagne une petite croix moulée sombre (le
  clip visible au centre d'une vraie bobine), et chaque vis gagne une fente (une ligne courte,
  chacune à un angle différent — de vraies vis ne sont jamais toutes orientées pareil).

Rien ici n'ajoute de nouvelle allocation par frame côté natif : la bande colorée passe par un
`Path` mis en cache (`cassetteBrandClipPath`, même raisonnement que `cassetteTapePath`), et le
rayon de la bobine est un simple `float` recalculé à partir de `cassetteProgress()` — pas de
Shader supplémentaire, `cassetteReelDiscShader` reste construit une seule fois.

**Une quatrième relecture : l'étiquette natale était restée vide.** Remonté avec une capture d'un
écran verrouillé réel comparée au rendu web : côté natif, l'étiquette restait un panneau uni — le
choix initial documenté plus haut (« pas de pochette : contenu trop lumineux pour un écran resté
allumé en continu ») s'est révélé faux dans la pratique : sans la pochette, l'écran verrouillé ne
dit plus quel morceau joue, ce qui est le seul point d'un affichage façon AOD. `drawCassette()`
dessine maintenant `vinylBitmap`/`vinylShader` — le même bitmap que « Vinyle », déjà alimenté par
`setAlbumArt()` à chaque changement de morceau, quel que soit le style actif — recadré en
« cover » sur l'étiquette et adouci par un `ColorMatrixColorFilter` construit une fois
(`cassetteArtColorFilter`, une approximation du `saturate(0.85) contrast(0.93) brightness(0.96)`
du CSS web) : la pochette, mais pas à pleine luminosité, comme une photo imprimée plutôt que
collée. Un panneau uni reste affiché tant qu'aucune pochette n'est encore arrivée.

## Correctif : la tâche partagée avec MainActivity coinçait `isForeground()` à vrai

Premier retour du terrain (Z Fold8 réel) : l'Edge Visualizer (les barres par-dessus Deezer)
cessait de réapparaître après un cycle verrouillage → visualiseur écran verrouillé →
déverrouillage, et seul un relancement de Vizuzik le débloquait.

Cause : `LockScreenVisualizerActivity` ne déclarait pas de `taskAffinity`, donc elle héritait par
défaut de celle de l'application entière — la même que `MainActivity`, qui n'en déclare pas non
plus. En `singleTask`, lancée avec `FLAG_ACTIVITY_NEW_TASK`, Android ne lui donne alors pas sa
propre tâche : il la pose au sommet de la tâche existante de `MainActivity` (celle-ci n'a pas
besoin d'être vivante pour compter — une entrée de tâche persiste dans les récents même après que
son Activity a été détruite par le système). En se fermant (`finish()` au déverrouillage), l'écran
retombe donc sur ce qu'il y a en dessous dans cette tâche *partagée* — `MainActivity` elle-même,
ressuscitée en silence (`onResume()` sans `onPause()` en face). `MainActivity.isForeground()`
reste alors coincé à vrai indéfiniment, et tout ce qui s'appuie dessus (`EdgeOverlayController`, et
ce contrôleur-ci) refuse de (re)démarrer jusqu'à ce que l'app soit rouverte puis quittée
proprement — exactement le symptôme rapporté.

Correctif : `android:taskAffinity=""` sur `LockScreenVisualizerActivity`, qui lui garantit sa
propre tâche isolée en toutes circonstances — se fermer ne peut alors plus jamais toucher la pile
de `MainActivity`. Avec ce correctif, l'overlay ne s'arrête même plus pendant le cycle : rien dans
son propre chemin de décision (`EdgeOverlayController`) ne dépend de cette Activity, donc il
continue de tourner sans interruption du début à la fin — il n'y a plus rien à faire « revenir ».

## Ce qui n'a pas été fait, et pourquoi

- **Pas de duplication du moteur de rendu.** Voir ci-dessus.
- **Pas de séquence d'accueil dédiée** (sheet d'explication, permissions demandées automatiquement
  au premier lancement) comme pour Edge Visualizer : contrairement à ce dernier, cette
  fonctionnalité est expérimentale, plus intrusive, et **désactivée par défaut** — elle vit
  entièrement dans le panneau de réglages, sur le même modèle que « Seulement sur l'écran du
  lecteur » (un interrupteur + un bouton d'autorisation qui n'apparaît que si le nécessaire manque).
- **Pas d'ancrage « Cocon » recentré sur l'écran.** Contrairement à « Vinyle »/« Cassette » (voir la
  mise à jour ci-dessus), « Cocon » continue de retomber sur Barres/Contour plutôt que de se centrer
  — non fait ici pour garder ce changement contenu à ce que le picker de cet écran propose
  réellement (Barres/Cassette/Disque, jamais Cocon) ; voir « Pistes non retenues ».

## Tests

**Aucun test manuel sur appareil dans cette session** — même contrainte que le reste de ce dépôt
(pas de SDK Android ni d'émulateur ici) et un risque plus élevé qu'à l'habitude, puisqu'il s'agit
cette fois d'interagir avec le verrouillage de l'écran. Un premier aller-retour réel sur le Z Fold8
a déjà fait remonter le bug de tâche partagée corrigé ci-dessus — le mécanisme de base (l'écran
apparaît bien par-dessus le verrouillage) fonctionne donc, mais rien n'a encore confirmé le
correctif lui-même, ni les cycles répétés (plusieurs allers-retours AOD → déverrouillage) qu'il vise
à réparer.

- La compilation a été vérifiée via le workflow GitHub Actions (`android.yml`, `assembleDebug`),
  comme chaque changement natif de ce dépôt qui ne peut pas être compilé localement.
- **Non vérifié visuellement** : que `setShowWhenLocked`/`setTurnScreenOn` amènent effectivement
  l'écran par-dessus le keyguard sans le dissoudre sur un Z Fold8/One UI récent ; que la diffusion
  `ACTION_SCREEN_OFF` arrive de façon fiable en pratique ; que l'intention plein écran se déclenche
  réellement une fois les deux permissions accordées (par opposition à un simple affichage en
  « heads-up ») ; le comportement sur écran plié/déplié ; l'interaction avec le vrai AOD du Z Fold8
  si l'utilisateur en a également un configuré (lequel l'emporte au réveil).
- À vérifier en priorité sur l'appareil cible avant de considérer cette fonctionnalité comme fiable.

## Pistes non retenues

- **Recentrer « Cocon »** sur l'écran verrouillé plutôt que de toujours retomber sur Barres/Contour.
  Techniquement identique à ce qui a depuis été fait pour « Vinyle »/« Cassette » (voir la mise à
  jour plus haut) — une position fixe au centre de l'écran plutôt que le modèle `ART_*` calé sur
  Deezer. Non fait : le picker propre à cet écran (`LockScreenVisualizerPreference`) n'offre que
  Barres/Cassette/Disque, jamais Cocon, donc rien n'appellerait jamais ce chemin ; l'ajouter serait
  du code mort tant que ce picker ne change pas.
- **Suivre l'accéléromètre/le capteur de proximité** pour un geste « lever pour réveiller », comme
  une vraie AOD. Demanderait un nouveau capteur, une nouvelle permission potentielle, et beaucoup
  plus de code pour un gain incertain sans pouvoir le tester sur l'appareil cible.
