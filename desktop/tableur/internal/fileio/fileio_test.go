package fileio

import (
	"strings"
	"testing"

	"github.com/docssuite/tableur/internal/engine"
	"github.com/docssuite/tableur/internal/sheet"
)

func book() *sheet.Book {
	b := sheet.New()
	b.Name = "Budget"
	b.Cells = map[string]string{
		"A1": "Produit", "B1": "Prix",
		"A2": "Pomme", "B2": "2.5",
		"A3": "Poire", "B3": "3",
		"B4": "=SOMME(B2:B3)",
	}
	b.Formats = map[string]sheet.Format{
		"A1": {Bold: true, Background: "#DDEEFF"},
		"B1": {Bold: true, Align: "right"},
	}
	return b
}

func TestJSONAllerRetour(t *testing.T) {
	data, err := SaveJSON(book())
	if err != nil {
		t.Fatal(err)
	}
	restored, err := LoadJSON(data)
	if err != nil {
		t.Fatal(err)
	}
	if restored.Name != "Budget" || restored.Cells["B4"] != "=SOMME(B2:B3)" {
		t.Errorf("classeur mal restauré : %+v", restored)
	}
	if !restored.Formats["A1"].Bold {
		t.Error("la mise en forme doit survivre")
	}
}

func TestCSVExporteLesValeurs(t *testing.T) {
	out := string(ExportCSV(book(), ';'))
	// Un CSV ne porte pas les formules : c'est le résultat qui est écrit.
	if !strings.Contains(out, "5.5") {
		t.Errorf("le total calculé devrait apparaître :\n%s", out)
	}
	if strings.Contains(out, "SOMME") {
		t.Errorf("la formule ne devrait pas être écrite :\n%s", out)
	}
}

func TestCSVAllerRetour(t *testing.T) {
	data := ExportCSV(book(), ';')
	restored, err := ImportCSV(data, ';')
	if err != nil {
		t.Fatal(err)
	}
	if restored.Cells["A2"] != "Pomme" || restored.Cells["B3"] != "3" {
		t.Errorf("CSV mal relu : %v", restored.Cells)
	}
}

func TestXLSXAllerRetour(t *testing.T) {
	data, err := ExportXLSX(book())
	if err != nil {
		t.Fatal(err)
	}
	if len(data) < 500 {
		t.Fatalf("classeur trop petit : %d octets", len(data))
	}
	restored, err := ImportXLSX(data)
	if err != nil {
		t.Fatal(err)
	}
	if restored.Cells["A2"] != "Pomme" {
		t.Errorf("texte perdu : %q", restored.Cells["A2"])
	}
	if restored.Cells["B2"] != "2.5" {
		t.Errorf("nombre perdu : %q", restored.Cells["B2"])
	}
	// La formule repart en français après le détour par l'anglais d'Excel.
	if restored.Cells["B4"] != "=SOMME(B2:B3)" {
		t.Errorf("formule perdue : %q", restored.Cells["B4"])
	}
}

func TestXLSXEcritLesFonctionsEnAnglais(t *testing.T) {
	data, _ := ExportXLSX(book())
	// Le contenu du zip doit porter SUM, qu'Excel comprend, et non SOMME.
	raw := string(data)
	if strings.Contains(raw, "SOMME") {
		t.Error("le nom français ne doit pas atterrir dans le fichier")
	}
}

func TestTraductionDesFormules(t *testing.T) {
	cases := [][2]string{
		{"=SOMME(A1:A3)", "=SUM(A1:A3)"},
		{"=SI(A1>1;\"SOMME\";MOYENNE(B1:B2))", "=IF(A1>1;\"SOMME\";AVERAGE(B1:B2))"},
		{"=RECHERCHEV(A1;B1:C9;2;FAUX)", "=VLOOKUP(A1;B1:C9;2;FALSE)"},
		{"=NB.SI(A1:A9;\">5\")", "=COUNTIF(A1:A9;\">5\")"},
	}
	for _, c := range cases {
		if got := engine.ToEnglish(c[0]); got != c[1] {
			t.Errorf("vers l'anglais : %q -> %q, attendu %q", c[0], got, c[1])
		}
		if got := engine.ToFrench(c[1]); got != c[0] {
			t.Errorf("retour au français : %q -> %q, attendu %q", c[1], got, c[0])
		}
	}
}

func TestTraductionIgnoreLesReferences(t *testing.T) {
	// SI est un nom de fonction, mais « SI1 » serait une référence.
	if got := engine.ToEnglish("=A1+B2"); got != "=A1+B2" {
		t.Errorf("références modifiées : %q", got)
	}
}

func TestImportXLSXRefuseUnFichierInvalide(t *testing.T) {
	if _, err := ImportXLSX([]byte("ceci n'est pas un zip")); err == nil {
		t.Error("un fichier invalide doit être refusé")
	}
}
