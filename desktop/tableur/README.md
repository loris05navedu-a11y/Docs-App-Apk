# Tableur — application Windows

Le tableur de DocsApp Suite, extrait en application de bureau autonome pour
Windows 10 et 11.

## Ce que c'est

Un seul fichier `Tableur.exe`, d'environ 8 Mo. Rien à installer, aucun runtime
à ajouter, aucune connexion : l'exécutable porte le moteur de calcul,
l'interface et tout ce qui les relie.

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

**Grille** — insertion et suppression de lignes et de colonnes, avec réécriture
des formules qui les désignent ; tri à partir de la cellule choisie ; gras,
italique, couleurs, alignement.

**Fichiers** — format natif `.tab`, export et import `.xlsx` et `.csv`. Les
noms de fonctions sont traduits en anglais à l'écriture d'un `.xlsx`, puisque
c'est ainsi qu'Excel les enregistre, et retraduits à la lecture.

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
main.go              démarrage : serveur local puis fenêtre
window_windows.go    fenêtre WebView2, densité d'écran
internal/engine      valeurs typées, analyseur, bibliothèque de fonctions
internal/sheet       classeur, insertion, suppression, tri
internal/fileio      .tab, .csv, .xlsx
internal/dialog      boîtes de fichiers Windows
internal/server      API locale
web/                 interface, embarquée dans l'exécutable
```
