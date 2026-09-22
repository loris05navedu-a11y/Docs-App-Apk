# Tableur — application Windows

Le tableur de DocsApp Suite, extrait en application de bureau autonome pour
Windows 10 et 11.

## Ce que c'est

Un seul fichier `Tableur.exe`, d'environ 8 Mo. Rien à installer, aucun runtime
à ajouter, aucune connexion : l'exécutable porte le moteur de calcul,
l'interface, l'icône et tout ce qui les relie.

La fenêtre s'appuie sur **WebView2**, le composant d'affichage qui accompagne
Microsoft Edge. Il est présent d'origine sur Windows 11 et sur Windows 10 à
jour. S'il manque, l'application ne refuse pas de démarrer : elle ouvre son
interface dans le navigateur par défaut.

## Fonctions

**Environ 90 fonctions de calcul**, en français comme en anglais :

| Famille | Exemples |
|---|---|
| Maths | SOMME, PRODUIT, ARRONDI(.SUP/.INF), TRONQUE, MOD, PLAFOND, PLANCHER, PGCD, PPCM, SOMMEPROD, FACT, ALEA |
| Statistiques | MOYENNE, MIN, MAX, NB, NBVAL, NB.VIDE, MEDIANE, MODE, ECARTYPE(P), VAR, GRANDE.VALEUR, RANG, CENTILE, QUARTILE |
| Conditions | SI, SI.CONDITIONS, SIERREUR, ET, OU, NON, SOMME.SI, NB.SI, MOYENNE.SI, ESTVIDE, ESTNUM, ESTTEXTE |
| Texte | GAUCHE, DROITE, STXT, NBCAR, MAJUSCULE, MINUSCULE, NOMPROPRE, SUPPRESPACE, SUBSTITUE, REMPLACER, TROUVE, CHERCHE, CONCATENER, JOINDRE.TEXTE, TEXTE, CNUM, EXACT |
| Recherche | RECHERCHEV, RECHERCHEH, INDEX, EQUIV, CHOISIR, LIGNES, COLONNES |
| Date | AUJOURDHUI, DATE, ANNEE, MOIS, JOUR, JOURSEM, JOURS, MOIS.DECALER, FIN.MOIS |
| Finance | VPM, VC, VA, NPM |

Également : l'opérateur `&` pour coller du texte, les pourcentages (`50%`), et
des erreurs qui nomment leur cause (`#DIV/0!`, `#N/A`, `#NOM?`, `#CYCLE`).

**Feuilles** — plusieurs feuilles par classeur, à ajouter, renommer, dupliquer,
déplacer et supprimer. Une formule peut désigner une autre feuille —
`=Feuille2!A1`, ou `='Ventes 2024'!A1` quand le nom comporte des espaces — et
renommer une feuille réécrit les formules qui la visaient.

**Graphiques** — dix types, liés aux cellules : le tracé suit les valeurs.

| Type | Ce qu'il montre |
|---|---|
| Boîte à moustache | Médiane, quartiles, moustaches selon Tukey (1,5 × écart interquartile), valeurs aberrantes isolées, moyenne en losange |
| Histogramme, barres horizontales | Une barre par catégorie, plusieurs séries côte à côte |
| Courbe, aires | Évolution d'une ou plusieurs séries |
| Secteurs, anneau | Répartition d'un total, avec légende et pourcentages |
| Nuage de points | Deux plages mises face à face |
| Distribution | Répartition en classes, nombre déduit par la règle de Sturges |
| Radar | Comparaison sur plusieurs axes |

**Grille** — insertion et suppression de lignes et de colonnes, avec réécriture
des formules qui les désignent ; tri à partir de la cellule choisie ; gras,
italique, couleurs, alignement.

**Fichiers** — format natif `.tab`, export et import `.xlsx` et `.csv`. Le
`.xlsx` porte toutes les feuilles. Les noms de fonctions sont traduits en
anglais à l'écriture, puisque c'est ainsi qu'Excel les enregistre, et
retraduits à la lecture.

## Raccourcis

| Touche | Effet |
|---|---|
| Flèches | Déplacer la sélection |
| Maj + flèches | Étendre la sélection |
| Entrée / F2 | Modifier la cellule |
| Entrée (en saisie) | Valider et descendre |
| Tab | Valider et aller à droite |
| Échap | Annuler la saisie |
| Suppr | Vider les cellules choisies |
| Frappe directe | Commence la saisie |
| Ctrl + S / O / N | Enregistrer, ouvrir, nouveau |
| Ctrl + B / I | Gras, italique |
| Double-clic sur un onglet | Renommer la feuille |
| Clic droit sur un onglet | Dupliquer, déplacer, supprimer |

## Compiler

La compilation se fait depuis n'importe quel système ; Go produit l'exécutable
Windows sans outillage supplémentaire.

```sh
./build.sh          # produit dist/Tableur.exe
```

Ou directement :

```sh
GOOS=windows GOARCH=amd64 CGO_ENABLED=0 \
  go build -ldflags="-H windowsgui -s -w" -o dist/Tableur.exe .
```

## Vérifier

```sh
go test ./...                                   # moteur, grille, fichiers
go run . -headless -addr 127.0.0.1:8777         # puis, dans un autre terminal :
node uitest/ui_test.js                          # pilote l'interface dans Chromium
```

Les tests Go couvrent le moteur de formules famille par famille, les
opérations de grille et les allers-retours de fichiers. Le test d'interface
ouvre la page réelle dans un navigateur et vérifie la saisie, le recalcul en
cascade, la mise en forme, l'assistant de fonctions, l'insertion de ligne et le
tri.

## Organisation

```
main.go                    démarrage : serveur local puis fenêtre
window_windows.go          fenêtre WebView2, densité d'écran
rsrc_windows_amd64.syso    icône liée dans l'exécutable
internal/engine            valeurs typées, analyseur, bibliothèque de fonctions
internal/sheet             classeur, feuilles, insertion, suppression, tri
internal/chart             résolution des plages, résumé à cinq nombres, classes
internal/fileio            .tab, .csv, .xlsx
internal/dialog            boîtes de fichiers Windows
internal/server            API locale
web/                       interface, embarquée dans l'exécutable
```

## Refaire l'icône

`icon.ico` est dessinée par `tools/icon.py`, puis liée à l'exécutable :

```sh
python3 tools/icon.py
rsrc -ico icon.ico -arch amd64 -o rsrc_windows_amd64.syso
```
