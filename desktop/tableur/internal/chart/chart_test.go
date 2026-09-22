package chart

import (
	"math"
	"testing"

	"github.com/docssuite/tableur/internal/engine"
)

func near(t *testing.T, got, want float64, label string) {
	t.Helper()
	if math.Abs(got-want) > 1e-9 {
		t.Errorf("%s : attendu %v, obtenu %v", label, want, got)
	}
}

func TestResumeCinqNombres(t *testing.T) {
	// Série simple et symétrique : 1..9, médiane 5, quartiles 3 et 7.
	box := Summarize("test", []float64{1, 2, 3, 4, 5, 6, 7, 8, 9})
	near(t, box.Median, 5, "médiane")
	near(t, box.Q1, 3, "premier quartile")
	near(t, box.Q3, 7, "troisième quartile")
	near(t, box.Min, 1, "minimum")
	near(t, box.Max, 9, "maximum")
	near(t, box.Mean, 5, "moyenne")
	if len(box.Outliers) != 0 {
		t.Errorf("aucune valeur aberrante attendue, obtenu %v", box.Outliers)
	}
	if box.Count != 9 {
		t.Errorf("effectif : %d", box.Count)
	}
}

func TestValeursAberrantes(t *testing.T) {
	// 100 est très au-delà de Q3 + 1,5 × écart interquartile.
	box := Summarize("test", []float64{1, 2, 3, 4, 5, 6, 7, 8, 9, 100})
	if len(box.Outliers) != 1 || box.Outliers[0] != 100 {
		t.Fatalf("la valeur extrême devrait être isolée, obtenu %v", box.Outliers)
	}
	// La moustache s'arrête à la dernière valeur non aberrante.
	near(t, box.Max, 9, "fin de moustache")
	near(t, box.Min, 1, "début de moustache")
}

func TestSerieConstante(t *testing.T) {
	// Écart interquartile nul : rien ne doit être déclaré aberrant.
	box := Summarize("test", []float64{4, 4, 4, 4})
	near(t, box.Median, 4, "médiane")
	near(t, box.Min, 4, "minimum")
	near(t, box.Max, 4, "maximum")
	if len(box.Outliers) != 0 {
		t.Errorf("aucune valeur aberrante attendue, obtenu %v", box.Outliers)
	}
}

func TestSerieVide(t *testing.T) {
	box := Summarize("vide", nil)
	if box.Count != 0 {
		t.Error("une série vide doit rester vide")
	}
}

func TestDistribution(t *testing.T) {
	bins := Distribute([]float64{1, 2, 3, 4, 5, 6, 7, 8, 9, 10}, 5)
	if len(bins) != 5 {
		t.Fatalf("5 classes attendues, %d obtenues", len(bins))
	}
	total := 0
	for _, b := range bins {
		total += b.Count
	}
	if total != 10 {
		t.Errorf("toutes les valeurs doivent être classées, %d comptées", total)
	}
	// La valeur maximale tombe dans la dernière classe, pas au-delà.
	if bins[4].Count == 0 {
		t.Error("la dernière classe devrait contenir le maximum")
	}
}

func TestDistributionChoisitSesClasses(t *testing.T) {
	bins := Distribute([]float64{1, 2, 3, 4, 5, 6, 7, 8}, 0)
	if len(bins) < 2 {
		t.Errorf("le nombre de classes doit être déduit, %d obtenu", len(bins))
	}
}

func TestResolveLitLesPlages(t *testing.T) {
	cells := engine.Cells{
		"A1": "Mois", "B1": "Ventes",
		"A2": "Jan", "B2": "10",
		"A3": "Fév", "B3": "20",
		"A4": "Mar", "B4": "=10*3",
	}
	data := Resolve("column", "Ventes", "A2:A4", []string{"B2:B4"}, 0, cells)
	if len(data.Series) != 1 {
		t.Fatalf("une série attendue, %d obtenue", len(data.Series))
	}
	if got := data.Series[0].Values; len(got) != 3 || got[2] != 30 {
		t.Errorf("les formules doivent être évaluées : %v", got)
	}
	if data.Labels[0] != "Jan" {
		t.Errorf("libellés : %v", data.Labels)
	}
	// L'en-tête au-dessus de la plage sert de nom de série.
	if data.Series[0].Name != "Ventes" {
		t.Errorf("nom de série : %q", data.Series[0].Name)
	}
}

func TestResolveIgnoreLeTexte(t *testing.T) {
	cells := engine.Cells{"B2": "10", "B3": "indisponible", "B4": "20"}
	data := Resolve("line", "", "", []string{"B2:B4"}, 0, cells)
	if got := data.Series[0].Values; len(got) != 2 {
		t.Errorf("le texte doit être écarté : %v", got)
	}
}

func TestResolveSansDonnees(t *testing.T) {
	data := Resolve("column", "", "", []string{"ZZ"}, 0, engine.Cells{})
	if data.Message == "" {
		t.Error("une plage vide doit être expliquée")
	}
}

func TestResolveBoiteAMoustache(t *testing.T) {
	cells := engine.Cells{}
	for i := 1; i <= 9; i++ {
		cells[engine.CellKey(i-1, 0)] = engine.FormatNumber(float64(i))
	}
	data := Resolve("box", "", "", []string{"A1:A9"}, 0, cells)
	if len(data.Boxes) != 1 {
		t.Fatalf("une boîte attendue, %d obtenue", len(data.Boxes))
	}
	near(t, data.Boxes[0].Median, 5, "médiane")
}

func TestTousLesTypesSontNommes(t *testing.T) {
	if len(Kinds) < 10 {
		t.Errorf("au moins dix types attendus, %d déclarés", len(Kinds))
	}
	for _, k := range Kinds {
		if k.ID == "" || k.Label == "" || k.Hint == "" {
			t.Errorf("type incomplet : %+v", k)
		}
	}
}
