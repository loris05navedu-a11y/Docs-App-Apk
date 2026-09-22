package sheet

import "testing"

func sample() *Book {
	b := New()
	b.Columns = 4
	b.Cells = map[string]string{
		"A1": "10", "B1": "Alpha",
		"A2": "30", "B2": "Charlie",
		"A3": "20", "B3": "Bravo",
		"C1": "=SOMME(A1:A3)",
	}
	b.Formats = map[string]Format{"A3": {Bold: true}}
	return b
}

func TestInsertRow(t *testing.T) {
	b := sample()
	b.InsertRow(1)
	if b.Cells["A1"] != "10" || b.Cells["A3"] != "30" || b.Cells["A4"] != "20" {
		t.Errorf("contenu mal décalé : %v", b.Cells)
	}
	if _, present := b.Cells["A2"]; present {
		t.Error("la ligne insérée devrait être vide")
	}
	// La plage doit s'étendre : les données occupent maintenant A1 à A4.
	if b.Cells["C1"] != "=SOMME(A1:A4)" {
		t.Errorf("formule non suivie : %q", b.Cells["C1"])
	}
	if !b.Formats["A4"].Bold {
		t.Error("la mise en forme doit suivre la cellule")
	}
}

func TestDeleteRow(t *testing.T) {
	b := sample()
	b.DeleteRow(1)
	if b.Cells["A1"] != "10" || b.Cells["A2"] != "20" {
		t.Errorf("contenu mal remonté : %v", b.Cells)
	}
	if b.Cells["C1"] != "=SOMME(A1:A2)" {
		t.Errorf("formule non suivie : %q", b.Cells["C1"])
	}

	pointing := New()
	pointing.Cells = map[string]string{"A2": "5", "B1": "=A2*2"}
	pointing.DeleteRow(1)
	if pointing.Cells["B1"] != "=#REF!*2" {
		t.Errorf("référence perdue non signalée : %q", pointing.Cells["B1"])
	}
}

func TestColumns(t *testing.T) {
	b := sample()
	b.InsertColumn(1)
	if b.Cells["A1"] != "10" || b.Cells["C1"] != "Alpha" || b.Cells["D1"] != "=SOMME(A1:A3)" {
		t.Errorf("insertion de colonne : %v", b.Cells)
	}

	d := sample()
	d.DeleteColumn(1)
	// La colonne B disparaît : l'ancienne C prend sa place, et sa formule ne
	// vise que la colonne A, donc elle ne bouge pas.
	if d.Cells["A1"] != "10" || d.Cells["B1"] != "=SOMME(A1:A3)" {
		t.Errorf("suppression de colonne : %v", d.Cells)
	}
	if _, present := d.Cells["C1"]; present {
		t.Error("C1 aurait dû être déplacée")
	}
}

func TestAdjustFormulaIgnoreLesChaines(t *testing.T) {
	got := AdjustFormula(`=SI(A2>1;"A2 est grand";A3)`, 1, 1, -1, 0)
	want := `=SI(A3>1;"A2 est grand";A4)`
	if got != want {
		t.Errorf("attendu %q, obtenu %q", want, got)
	}
}

func TestAdjustFormulaIgnoreLesFonctions(t *testing.T) {
	if got := AdjustFormula("=SOMME(A1:A3)", 0, 1, -1, 0); got != "=SOMME(A2:A4)" {
		t.Errorf("SOMME : %q", got)
	}
	if got := AdjustFormula("=LOG10(A1)", 0, 1, -1, 0); got != "=LOG10(A2)" {
		t.Errorf("LOG10 pris pour une référence : %q", got)
	}
}

func TestSort(t *testing.T) {
	b := sample()
	b.Sort(0, 0, 2, true)
	if b.Cells["A1"] != "10" || b.Cells["A2"] != "20" || b.Cells["A3"] != "30" {
		t.Errorf("tri croissant : %v", b.Cells)
	}
	// Chaque ligne emporte ses voisines de droite.
	if b.Cells["B1"] != "Alpha" || b.Cells["B2"] != "Bravo" || b.Cells["B3"] != "Charlie" {
		t.Errorf("les lignes n'ont pas suivi : %v", b.Cells)
	}

	d := sample()
	d.Sort(0, 0, 2, false)
	if d.Cells["A1"] != "30" || d.Cells["A3"] != "10" {
		t.Errorf("tri décroissant : %v", d.Cells)
	}
}

func TestSortVidesEnDernier(t *testing.T) {
	b := New()
	b.Columns = 1
	b.Cells = map[string]string{"A1": "b", "A3": "a"}
	b.Sort(0, 0, 2, true)
	if b.Cells["A1"] != "a" || b.Cells["A2"] != "b" {
		t.Errorf("les vides doivent finir en dernier : %v", b.Cells)
	}
	if _, present := b.Cells["A3"]; present {
		t.Error("A3 devrait rester vide")
	}
}

func TestSetEffaceUneSaisieVide(t *testing.T) {
	b := New()
	b.Set("A1", "x")
	b.Set("A1", "")
	if _, present := b.Cells["A1"]; present {
		t.Error("une saisie vide doit effacer la case")
	}
}
