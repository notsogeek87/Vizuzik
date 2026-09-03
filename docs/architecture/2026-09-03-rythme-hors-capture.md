# Le rythme hors capture : ne rien inventer

**Statut :** adopté — 3 septembre 2026
**Concerne :** `src/visualizer.js`, `src/main.js`, `src/style.css`

**Pour qui :** toute personne qui touche à l'animation quand la capture audio n'est pas
active, ou qui se demande pourquoi l'écran est plus calme qu'avant dans ce cas.

## Le contexte

Vizuzik n'entend la musique que si l'utilisateur a accordé la capture système (voir
[Le consentement de capture audio](2026-09-03-consentement-capture-audio.md)). Sans elle, le
moteur produisait un « groove » de substitution : un kick synthétique dont le BPM était tiré au
hasard entre 96 et 140, **retiré à chaque battement**. Ce faux kick traversait ensuite le vrai
détecteur de rythme et pilotait tout : lueur du disque, ondes de choc, gerbes de particules,
opacité des nuages de couleur, anneau du bouton lecture (lui, sur une boucle CSS fixe de 1,6 s,
soit 37 BPM).

## Le problème

Une pulsation inventée n'est pas neutre : l'oreille la compare en permanence à celle qu'elle
entend. Le résultat n'est pas perçu comme « une animation générique », mais comme **une
application qui rate le rythme**. C'est pire que l'absence de mouvement rythmé, et c'est
d'autant plus visible que ce sont les *couleurs* et les *lueurs* qui pulsaient — les éléments
les plus larges de l'écran.

## La décision

Hors capture, le moteur passe en **régime ambiant** et n'affirme aucun tempo.

1. **Plus de kick synthétique, plus de détection de rythme.** `_updateAmbient()` remplace
   `_updateSynthetic()` : trois oscillateurs par bande, de périodes sans multiple commun
   (≈30 s, ≈48 s, ≈82 s), avec un lissage lent et symétrique. Le lissage asymétrique du régime
   live (attaque immédiate) est précisément ce qui transformerait ces vagues en frappes.
   `beatEnergy` ne peut plus monter tout seul.
2. **Les couleurs voyagent au lieu de clignoter.** `colorShift` avance d'une couleur de la
   palette toutes les 26 s ; `displayColor()` interpole entre deux accents de la pochette. Toute
   la scène glisse d'une teinte à l'autre, trop lentement pour qu'on voie le changement se
   produire. `src/main.js` relit ces couleurs pour `--c1`/`--c2`/`--c3`, si bien que le DOM et
   le canvas restent de la même couleur.
3. **Les impulsions ne viennent plus que du réel.** `pulse(strength)` est appelé sur un
   changement de morceau, un play/pause, un glissement, un changement de mode. Ce sont les
   seuls moments où l'écran a le droit de frapper, et ils sont vrais par construction.
4. **L'anneau du bouton lecture suit `--beat`** au lieu d'une boucle de 1,6 s. Cette boucle
   était une affirmation de tempo fausse pour n'importe quel morceau, capture active ou non.
5. **La lueur du disque respire sur `--level`**, qui reste une marée lente : le disque ne se
   fige pas entre deux impulsions.

Ce qui ne change pas : le disque tourne exactement comme avant, la pochette reste le sujet, et
les cinq scènes sont les mêmes.

## Les conséquences

- La différence entre avec et sans capture devient franche : calme et coulant d'un côté, sur le
  rythme de l'autre. Le badge « Activer le son réel » promet donc quelque chose de visible.
- `setPalette()` n'émet plus d'onde de choc : c'est `pulse()`, déclenché sur le morceau et non
  sur la pochette, qui marque le changement de titre — deux morceaux partageant une pochette
  restent distincts.
- Une scène qui voudrait un effet rythmique doit vérifier `isLive` : sans capture, il n'y a
  aucun rythme à suivre, et il ne faut pas en fabriquer un localement.

## Les options écartées

- **Garder le rythme simulé mais plus discret.** Une pulsation fausse et faible reste une
  pulsation fausse ; le décalage se remarque encore.
- **Se caler sur la progression du morceau** (position/durée, dont on dispose) pour un balayage
  de lumière par tour de disque. Honnête et séduisant, mais c'est un ajout visuel, pas un
  correctif : à reconsidérer si l'écran paraît trop sage.
