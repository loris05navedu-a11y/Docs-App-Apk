package engine

import (
	"math"
	"testing"
)

var sheet = Cells{
	// Un petit catalogue : produit, catégorie, prix, stock.
	"A1": "Pomme", "B1": "Fruit", "C1": "2.5", "D1": "10",
	"A2": "Pain", "B2": "Boulangerie", "C2": "1.2", "D2": "4",
	"A3": "Poire", "B3": "Fruit", "C3": "3", "D3": "7",
	"A4": "Lait", "B4": "Crèmerie", "C4": "0.9", "D4": "0",
}

func text(t *testing.T, formula string) string {
	t.Helper()
	v, ok := Evaluate(formula, sheet)
	if !ok {
		t.Fatalf("formule refusée : %s", formula)
	}
	return v.AsText()
}

func number(t *testing.T, formula string) float64 {
	t.Helper()
	v, ok := Evaluate(formula, sheet)
	if !ok {
		t.Fatalf("formule refusée : %s", formula)
	}
	n, isNum := v.AsNumber()
	if !isNum {
		t.Fatalf("%s n'a pas rendu un nombre mais %q", formula, v.AsText())
	}
	return n
}

func near(t *testing.T, got, want, tol float64, label string) {
	t.Helper()
	if math.Abs(got-want) > tol {
		t.Errorf("%s : attendu %v, obtenu %v", label, want, got)
	}
}

func equal(t *testing.T, got, want, label string) {
	t.Helper()
	if got != want {
		t.Errorf("%s : attendu %q, obtenu %q", label, want, got)
	}
}

func TestTexte(t *testing.T) {
	equal(t, text(t, `GAUCHE(A1;3)`), "Pom", "GAUCHE")
	equal(t, text(t, `DROITE(A1;2)`), "me", "DROITE")
	equal(t, text(t, `STXT(A1;2;3)`), "omm", "STXT")
	near(t, number(t, `NBCAR(A1)`), 5, 0, "NBCAR")
	equal(t, text(t, `MAJUSCULE(A1)`), "POMME", "MAJUSCULE")
	equal(t, text(t, `NOMPROPRE("jean DUPONT")`), "Jean Dupont", "NOMPROPRE")
	equal(t, text(t, `SUPPRESPACE("  a    b  ")`), "a b", "SUPPRESPACE")
	equal(t, text(t, `CONCATENER(A1;"-";B1)`), "Pomme-Fruit", "CONCATENER")
	equal(t, text(t, `A1&"-"&B1`), "Pomme-Fruit", "opérateur &")
	equal(t, text(t, `SUBSTITUE("Pomme de air";"air";"terre")`), "Pomme de terre", "SUBSTITUE")
	equal(t, text(t, `REPT("abc";2)`), "abcabc", "REPT")
	near(t, number(t, `TROUVE("m";A1)`), 3, 0, "TROUVE")
	equal(t, text(t, `TROUVE("z";A1)`), "#N/A", "TROUVE absent")
	equal(t, text(t, `JOINDRE.TEXTE(";";VRAI;A1:A2)`), "Pomme;Pain", "JOINDRE.TEXTE")
}

func TestTypes(t *testing.T) {
	equal(t, text(t, `EXACT("a";"a")`), "VRAI", "EXACT identique")
	equal(t, text(t, `EXACT("a";"A")`), "FAUX", "EXACT casse")
	equal(t, text(t, `ESTTEXTE(A1)`), "VRAI", "ESTTEXTE")
	equal(t, text(t, `ESTNUM(A1)`), "FAUX", "ESTNUM sur texte")
	equal(t, text(t, `ESTNUM(C1)`), "VRAI", "ESTNUM sur nombre")
	equal(t, text(t, `ESTVIDE(Z9)`), "VRAI", "ESTVIDE")
}

func TestConditions(t *testing.T) {
	near(t, number(t, `NB.SI(B1:B4;"Fruit")`), 2, 0, "NB.SI")
	near(t, number(t, `SOMME.SI(B1:B4;"Fruit";C1:C4)`), 5.5, 1e-9, "SOMME.SI")
	near(t, number(t, `MOYENNE.SI(B1:B4;"Fruit";C1:C4)`), 2.75, 1e-9, "MOYENNE.SI")
	near(t, number(t, `NB.SI(D1:D4;">5")`), 2, 0, "NB.SI >")
	near(t, number(t, `NB.SI(D1:D4;"<>0")`), 3, 0, "NB.SI <>")
	near(t, number(t, `NB.SI(A1:A4;"Po*")`), 2, 0, "NB.SI joker")
}

func TestErreurs(t *testing.T) {
	equal(t, text(t, `1/0`), "#DIV/0!", "division par zéro")
	equal(t, text(t, `SIERREUR(1/0;"secours")`), "secours", "SIERREUR")
	near(t, number(t, `SIERREUR(2+2;"secours")`), 4, 0, "SIERREUR sans erreur")
	equal(t, text(t, `ESTERREUR(1/0)`), "VRAI", "ESTERREUR")
	equal(t, text(t, `BIDULE(1)`), "#NOM?", "fonction inconnue")
}

func TestLogique(t *testing.T) {
	equal(t, text(t, `ET(1=1;2>1)`), "VRAI", "ET")
	equal(t, text(t, `ET(1=1;2<1)`), "FAUX", "ET faux")
	equal(t, text(t, `OU(1=2;2>1)`), "VRAI", "OU")
	equal(t, text(t, `NON(1=2)`), "VRAI", "NON")
	equal(t, text(t, `SI(C3>2;"cher";"bon marché")`), "cher", "SI")
	equal(t, text(t, `SI.CONDITIONS(C1>10;"cher";C1>2;"moyen";VRAI;"bas")`), "moyen", "SI.CONDITIONS")
}

func TestRecherche(t *testing.T) {
	near(t, number(t, `RECHERCHEV("Poire";A1:D4;3;FAUX)`), 3, 1e-9, "RECHERCHEV")
	equal(t, text(t, `RECHERCHEV("Lait";A1:D4;2;FAUX)`), "Crèmerie", "RECHERCHEV texte")
	equal(t, text(t, `RECHERCHEV("Kiwi";A1:D4;2;FAUX)`), "#N/A", "RECHERCHEV absent")
	near(t, number(t, `EQUIV("Poire";A1:A4;0)`), 3, 0, "EQUIV")
	equal(t, text(t, `INDEX(A1:A4;3)`), "Poire", "INDEX colonne")
	near(t, number(t, `INDEX(A1:D4;3;3)`), 3, 1e-9, "INDEX grille")
	near(t, number(t, `INDEX(C1:C4;EQUIV("Pain";A1:A4;0))`), 1.2, 1e-9, "INDEX+EQUIV")
	equal(t, text(t, `CHOISIR(2;A1;A2;A3)`), "Pain", "CHOISIR")
	near(t, number(t, `LIGNES(A1:D4)`), 4, 0, "LIGNES")
	near(t, number(t, `COLONNES(A1:D4)`), 4, 0, "COLONNES")
}

func TestDates(t *testing.T) {
	near(t, number(t, `DATE(2024;3;15)`), 45366, 0, "sérial de date")
	near(t, number(t, `ANNEE(DATE(2024;3;15))`), 2024, 0, "ANNEE")
	near(t, number(t, `MOIS(DATE(2024;3;15))`), 3, 0, "MOIS")
	near(t, number(t, `JOUR(DATE(2024;3;15))`), 15, 0, "JOUR")
	near(t, number(t, `JOURS(DATE(2024;4;15);DATE(2024;3;15))`), 31, 0, "JOURS")
	near(t, number(t, `MOIS(MOIS.DECALER(DATE(2024;3;15);1))`), 4, 0, "MOIS.DECALER")
	near(t, number(t, `JOUR(FIN.MOIS(DATE(2024;3;15);0))`), 31, 0, "FIN.MOIS")
	equal(t, text(t, `TEXTE(DATE(2024;3;15);"jj/mm/aaaa")`), "15/03/2024", "TEXTE date")
}

func TestMaths(t *testing.T) {
	near(t, number(t, `ARRONDI(2.345;2)`), 2.35, 1e-9, "ARRONDI")
	near(t, number(t, `ARRONDI.SUP(2.31;1)`), 2.4, 1e-9, "ARRONDI.SUP")
	near(t, number(t, `ARRONDI.INF(2.39;1)`), 2.3, 1e-9, "ARRONDI.INF")
	near(t, number(t, `TRONQUE(2.99)`), 2, 1e-9, "TRONQUE")
	near(t, number(t, `PLAFOND(7;5)`), 10, 1e-9, "PLAFOND")
	near(t, number(t, `PLANCHER(7;5)`), 5, 1e-9, "PLANCHER")
	near(t, number(t, `PAIR(3.1)`), 4, 1e-9, "PAIR")
	near(t, number(t, `IMPAIR(3.1)`), 5, 1e-9, "IMPAIR")
	near(t, number(t, `MOD(-4;3)`), 2, 1e-9, "MOD signe du diviseur")
	near(t, number(t, `FACT(5)`), 120, 1e-9, "FACT")
	near(t, number(t, `PGCD(12;18)`), 6, 1e-9, "PGCD")
	near(t, number(t, `PPCM(12;18)`), 36, 1e-9, "PPCM")
	near(t, number(t, `50%`), 0.5, 1e-9, "pourcentage")
	near(t, number(t, `2+3*4`), 14, 1e-9, "priorité des opérateurs")
	near(t, number(t, `RACINE(16)`), 4, 1e-9, "RACINE")
	near(t, number(t, `LOG(100)`), 2, 1e-9, "LOG base 10 par défaut")
}

func TestSommeProd(t *testing.T) {
	// Prix × stock : 2,5×10 + 1,2×4 + 3×7 + 0,9×0
	near(t, number(t, `SOMMEPROD(C1:C4;D1:D4)`), 50.8, 1e-9, "SOMMEPROD")
}

func TestStatistiques(t *testing.T) {
	near(t, number(t, `NB(C1:C4)`), 4, 0, "NB")
	near(t, number(t, `NBVAL(A1:A4)`), 4, 0, "NBVAL")
	near(t, number(t, `NB(A1:A4)`), 0, 0, "NB ignore le texte")
	near(t, number(t, `GRANDE.VALEUR(C1:C4;1)`), 3, 1e-9, "GRANDE.VALEUR")
	near(t, number(t, `PETITE.VALEUR(C1:C4;1)`), 0.9, 1e-9, "PETITE.VALEUR")
	near(t, number(t, `RANG(2.5;C1:C4)`), 2, 0, "RANG")
	near(t, number(t, `MEDIANE(C1:C4)`), 1.85, 1e-9, "MEDIANE")
	near(t, number(t, `MOYENNE(C1:C4)`), 1.9, 1e-9, "MOYENNE")
}

func TestCasesVides(t *testing.T) {
	partial := Cells{"A1": "1", "A3": "3"}
	v, _ := Evaluate(`NB.VIDE(A1:A3)`, partial)
	n, _ := v.AsNumber()
	near(t, n, 1, 0, "NB.VIDE")
	v, _ = Evaluate(`NBVAL(A1:A3)`, partial)
	n, _ = v.AsNumber()
	near(t, n, 2, 0, "NBVAL")
}

func TestFinance(t *testing.T) {
	// 10 000 € sur 12 mois à 0,5 % par mois.
	near(t, number(t, `VPM(0.005;12;10000)`), -860.66, 0.01, "VPM")
	near(t, number(t, `VPM(0;10;10000)`), -1000, 0.01, "VPM taux nul")
}

func TestFormulesInvalides(t *testing.T) {
	for _, formula := range []string{"(((", "SOMME(", "", "   ", "3+"} {
		if _, ok := Evaluate(formula, sheet); ok {
			t.Errorf("« %s » aurait dû être refusée", formula)
		}
	}
}

func TestCycle(t *testing.T) {
	cycle := Cells{"A1": "=A2", "A2": "=A1"}
	equal(t, Display("A1", cycle), "#CYCLE", "référence circulaire")
	direct := Cells{"Z1": "=Z1+1"}
	equal(t, Display("Z1", direct), "#CYCLE", "auto-référence")
}

func TestAffichage(t *testing.T) {
	cells := Cells{"A1": "2", "A2": "3", "B1": "=A1+A2", "C1": "bonjour"}
	equal(t, Display("B1", cells), "5", "formule affichée")
	equal(t, Display("C1", cells), "bonjour", "texte affiché tel quel")
	equal(t, Display("Z9", cells), "", "case vide")
}

func TestCatalogueComplet(t *testing.T) {
	if len(Catalog) != 7 {
		t.Fatalf("7 familles attendues, %d trouvées", len(Catalog))
	}
	total := 0
	for _, group := range Catalog {
		for _, entry := range group.Entries {
			total++
			if entry.Name() == "" || entry.Description == "" {
				t.Errorf("entrée incomplète : %+v", entry)
			}
			// Chaque fonction annoncée doit exister dans le moteur.
			if v, _ := Evaluate(entry.Name()+"()", sheet); v.AsText() == "#NOM?" {
				t.Errorf("fonction annoncée mais absente : %s", entry.Name())
			}
		}
	}
	if total < 80 {
		t.Errorf("catalogue trop maigre : %d entrées", total)
	}
}

func TestReferences(t *testing.T) {
	equal(t, ColumnLabel(0), "A", "colonne 0")
	equal(t, ColumnLabel(25), "Z", "colonne 25")
	equal(t, ColumnLabel(26), "AA", "colonne 26")
	if ColumnIndex("AA") != 26 {
		t.Error("ColumnIndex AA")
	}
	if got := ExpandRange("A1:B2"); len(got) != 4 || got[0] != "A1" || got[3] != "B2" {
		t.Errorf("ExpandRange : %v", got)
	}
	if ExpandRange("bonjour") != nil {
		t.Error("une plage invalide doit être vide")
	}
}
