package sheet

import (
	"testing"

	"github.com/docssuite/tableur/internal/engine"
)

func TestNouveauClasseurAUneFeuille(t *testing.T) {
	w := New()
	if len(w.Sheets) != 1 || w.Current().Name != "Feuille1" {
		t.Errorf("classeur initial : %+v", w.Sheets)
	}
}

func TestAjoutEtNommageDesFeuilles(t *testing.T) {
	w := New()
	w.AddSheet()
	w.AddSheet()
	if len(w.Sheets) != 3 {
		t.Fatalf("3 feuilles attendues, %d obtenues", len(w.Sheets))
	}
	names := []string{w.Sheets[0].Name, w.Sheets[1].Name, w.Sheets[2].Name}
	if names[0] != "Feuille1" || names[1] != "Feuille2" || names[2] != "Feuille3" {
		t.Errorf("noms attribués : %v", names)
	}
	// La nouvelle feuille devient l'active.
	if w.Active != 2 {
		t.Errorf("la feuille ajoutée devrait être active, active = %d", w.Active)
	}
}

func TestSuppressionGardeAuMoinsUneFeuille(t *testing.T) {
	w := New()
	if err := w.RemoveSheet(0); err == nil {
		t.Error("supprimer la dernière feuille doit être refusé")
	}
	w.AddSheet()
	if err := w.RemoveSheet(0); err != nil {
		t.Fatal(err)
	}
	if len(w.Sheets) != 1 || w.Active != 0 {
		t.Errorf("après suppression : %d feuilles, active %d", len(w.Sheets), w.Active)
	}
}

func TestReferenceEntreFeuilles(t *testing.T) {
	w := New()
	w.Sheets[0].Cells["A1"] = "10"
	second := w.AddSheet()
	second.Cells["A1"] = "5"
	second.Cells["B1"] = "=Feuille1!A1*2"

	cells := w.EngineCells()
	if got := engine.Display("B1", cells); got != "20" {
		t.Errorf("référence entre feuilles : attendu 20, obtenu %q", got)
	}
	// La feuille active fournit aussi les noms courts.
	if got := engine.Display("A1", cells); got != "5" {
		t.Errorf("la feuille active doit primer : %q", got)
	}
}

func TestPlageEntreFeuilles(t *testing.T) {
	w := New()
	for i := 0; i < 3; i++ {
		w.Sheets[0].Cells[engine.CellKey(i, 0)] = engine.FormatNumber(float64(i + 1))
	}
	second := w.AddSheet()
	second.Cells["A1"] = "=SOMME(Feuille1!A1:A3)"

	if got := engine.Display("A1", w.EngineCells()); got != "6" {
		t.Errorf("plage entre feuilles : attendu 6, obtenu %q", got)
	}
}

func TestNomAvecEspacesEntreApostrophes(t *testing.T) {
	w := New()
	if err := w.RenameSheet(0, "Ventes 2024"); err != nil {
		t.Fatal(err)
	}
	w.Sheets[0].Cells["A1"] = "7"
	second := w.AddSheet()
	second.Cells["A1"] = "='Ventes 2024'!A1+1"

	if got := engine.Display("A1", w.EngineCells()); got != "8" {
		t.Errorf("nom entre apostrophes : attendu 8, obtenu %q", got)
	}
}

func TestRenommerSuitLesFormules(t *testing.T) {
	w := New()
	w.Sheets[0].Cells["A1"] = "3"
	second := w.AddSheet()
	second.Cells["B1"] = "=Feuille1!A1*10"

	if err := w.RenameSheet(0, "Donnees"); err != nil {
		t.Fatal(err)
	}
	if second.Cells["B1"] != "=Donnees!A1*10" {
		t.Errorf("formule non reprise : %q", second.Cells["B1"])
	}
	if got := engine.Display("B1", w.EngineCells()); got != "30" {
		t.Errorf("le calcul doit continuer : %q", got)
	}
}

func TestRenommerRefuseLesNomsImpossibles(t *testing.T) {
	w := New()
	w.AddSheet()
	for _, name := range []string{"", "   ", "Feuille2", "Mauvais!Nom"} {
		if err := w.RenameSheet(0, name); err == nil {
			t.Errorf("le nom %q aurait dû être refusé", name)
		}
	}
}

func TestDeplacerUneFeuille(t *testing.T) {
	w := New()
	w.AddSheet()
	w.AddSheet()
	first := w.Sheets[0].Name
	if err := w.MoveSheet(0, 2); err != nil {
		t.Fatal(err)
	}
	if w.Sheets[2].Name != first {
		t.Errorf("ordre après déplacement : %v", []string{w.Sheets[0].Name, w.Sheets[1].Name, w.Sheets[2].Name})
	}
}

func TestInsertionNeDecalePasLesAutresFeuilles(t *testing.T) {
	// Insérer une ligne dans Feuille2 ne doit pas toucher à ce qui vise
	// Feuille1 : seules les références locales se décalent.
	s := NewSheet("Feuille2")
	s.Cells["B1"] = "=Feuille1!A1+A2"
	s.InsertRow(0)
	if got := s.Cells["B2"]; got != "=Feuille1!A1+A3" {
		t.Errorf("références mal reprises : %q", got)
	}
}
