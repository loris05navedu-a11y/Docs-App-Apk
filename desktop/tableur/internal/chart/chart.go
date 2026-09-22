// Package chart transforme des plages de cellules en données prêtes à tracer.
//
// Le calcul reste ici, du côté Go : c'est le même moteur qui résout les plages
// et les formules, et les statistiques d'une boîte à moustache se vérifient
// bien mieux ici que dans le navigateur.
package chart

import (
	"math"
	"sort"

	"github.com/docssuite/tableur/internal/engine"
)

// Kinds énumère les types proposés, dans l'ordre d'affichage.
var Kinds = []struct {
	ID    string `json:"id"`
	Label string `json:"label"`
	Hint  string `json:"hint"`
}{
	{"box", "Boîte à moustache", "Médiane, quartiles et valeurs aberrantes"},
	{"column", "Histogramme", "Barres verticales, une par catégorie"},
	{"bar", "Barres horizontales", "Utile quand les libellés sont longs"},
	{"line", "Courbe", "Évolution d'une série"},
	{"area", "Aires", "Courbe remplie, pour des volumes"},
	{"pie", "Secteurs", "Répartition d'un total"},
	{"donut", "Anneau", "Répartition, avec le total au centre"},
	{"scatter", "Nuage de points", "Deux séries mises face à face"},
	{"histogram", "Distribution", "Répartition d'une série en classes"},
	{"radar", "Radar", "Comparaison sur plusieurs axes"},
}

// Series est une suite de valeurs nommée.
type Series struct {
	Name   string    `json:"name"`
	Values []float64 `json:"values"`
}

// Box résume une série pour la boîte à moustache.
type Box struct {
	Name     string    `json:"name"`
	Min      float64   `json:"min"`
	Q1       float64   `json:"q1"`
	Median   float64   `json:"median"`
	Q3       float64   `json:"q3"`
	Max      float64   `json:"max"`
	Mean     float64   `json:"mean"`
	Outliers []float64 `json:"outliers"`
	Count    int       `json:"count"`
}

// Bin est une classe de la distribution.
type Bin struct {
	From  float64 `json:"from"`
	To    float64 `json:"to"`
	Count int     `json:"count"`
}

// Data est ce que l'interface reçoit pour tracer.
type Data struct {
	Kind    string   `json:"kind"`
	Title   string   `json:"title"`
	Labels  []string `json:"labels"`
	Series  []Series `json:"series"`
	Boxes   []Box    `json:"boxes,omitempty"`
	Bins    []Bin    `json:"bins,omitempty"`
	Message string   `json:"message,omitempty"`
}

// Resolve lit les plages et prépare les données du graphique.
func Resolve(kind, title, labelsRange string, seriesRanges []string, bins int, cells engine.Cells) Data {
	data := Data{Kind: kind, Title: title}

	data.Labels = readTexts(labelsRange, cells)
	for _, spec := range seriesRanges {
		refs := engine.ExpandRange(spec)
		if len(refs) == 0 {
			continue
		}
		data.Series = append(data.Series, Series{
			Name:   seriesName(spec, refs, cells),
			Values: readNumbers(refs, cells),
		})
	}

	if len(data.Series) == 0 {
		data.Message = "Aucune donnée : vérifiez la plage de valeurs."
		return data
	}

	switch kind {
	case "box":
		for _, s := range data.Series {
			data.Boxes = append(data.Boxes, Summarize(s.Name, s.Values))
		}
	case "histogram":
		data.Bins = Distribute(data.Series[0].Values, bins)
	}

	// Les libellés manquants sont complétés pour que l'axe reste lisible.
	if len(data.Series) > 0 {
		longest := 0
		for _, s := range data.Series {
			if len(s.Values) > longest {
				longest = len(s.Values)
			}
		}
		for len(data.Labels) < longest {
			data.Labels = append(data.Labels, "")
		}
	}
	return data
}

// seriesName reprend l'en-tête de colonne quand la plage en a un.
func seriesName(spec string, refs []string, cells engine.Cells) string {
	if len(refs) == 0 {
		return spec
	}
	row, col, ok := engine.SplitRef(refs[0])
	if ok && row > 0 {
		above := engine.Display(engine.CellKey(row-1, col), cells)
		if above != "" && engine.Literal(above).Kind != engine.KindNum {
			return above
		}
	}
	return spec
}

func readTexts(spec string, cells engine.Cells) []string {
	refs := engine.ExpandRange(spec)
	out := make([]string, 0, len(refs))
	for _, ref := range refs {
		out = append(out, engine.Display(ref, cells))
	}
	return out
}

// readNumbers ignore les cases vides et le texte : une colonne avec un titre
// ou un trou ne doit pas décaler la série.
func readNumbers(refs []string, cells engine.Cells) []float64 {
	out := make([]float64, 0, len(refs))
	for _, ref := range refs {
		shown := engine.Display(ref, cells)
		if v := engine.Literal(shown); v.Kind == engine.KindNum {
			out = append(out, v.Num)
		}
	}
	return out
}

/*
Summarize calcule le résumé à cinq nombres.

Les moustaches s'arrêtent à la dernière valeur située à moins d'une fois et
demie l'écart interquartile des bords de la boîte ; au-delà, les points sont
signalés à part. C'est la convention de Tukey, celle qu'on attend d'une boîte
à moustache.
*/
func Summarize(name string, values []float64) Box {
	box := Box{Name: name, Count: len(values)}
	if len(values) == 0 {
		return box
	}
	sorted := append([]float64(nil), values...)
	sort.Float64s(sorted)

	box.Q1 = quantile(sorted, 0.25)
	box.Median = quantile(sorted, 0.5)
	box.Q3 = quantile(sorted, 0.75)
	total := 0.0
	for _, v := range sorted {
		total += v
	}
	box.Mean = total / float64(len(sorted))

	spread := box.Q3 - box.Q1
	low := box.Q1 - 1.5*spread
	high := box.Q3 + 1.5*spread

	box.Min, box.Max = math.Inf(1), math.Inf(-1)
	for _, v := range sorted {
		if v < low || v > high {
			box.Outliers = append(box.Outliers, v)
			continue
		}
		if v < box.Min {
			box.Min = v
		}
		if v > box.Max {
			box.Max = v
		}
	}
	// Une série entièrement composée de points extrêmes ne peut pas exister :
	// la garde ne sert que si l'écart interquartile est nul.
	if math.IsInf(box.Min, 1) {
		box.Min, box.Max = sorted[0], sorted[len(sorted)-1]
		box.Outliers = nil
	}
	return box
}

// Distribute répartit une série en classes de même largeur.
func Distribute(values []float64, count int) []Bin {
	if len(values) == 0 {
		return nil
	}
	if count < 2 {
		// Règle de Sturges : un nombre de classes proportionné à l'effectif.
		count = int(math.Ceil(math.Log2(float64(len(values))) + 1))
	}
	if count < 2 {
		count = 2
	}
	if count > 40 {
		count = 40
	}

	low, high := values[0], values[0]
	for _, v := range values {
		if v < low {
			low = v
		}
		if v > high {
			high = v
		}
	}
	if high == low {
		high = low + 1
	}
	width := (high - low) / float64(count)

	bins := make([]Bin, count)
	for i := range bins {
		bins[i] = Bin{From: low + float64(i)*width, To: low + float64(i+1)*width}
	}
	for _, v := range values {
		index := int((v - low) / width)
		if index >= count {
			index = count - 1 // la valeur maximale tombe dans la dernière classe
		}
		if index < 0 {
			index = 0
		}
		bins[index].Count++
	}
	return bins
}

// quantile interpole linéairement entre les deux rangs encadrants.
func quantile(sorted []float64, p float64) float64 {
	if len(sorted) == 0 {
		return 0
	}
	if len(sorted) == 1 {
		return sorted[0]
	}
	pos := p * float64(len(sorted)-1)
	lo := int(math.Floor(pos))
	hi := int(math.Ceil(pos))
	if lo == hi {
		return sorted[lo]
	}
	return sorted[lo] + (sorted[hi]-sorted[lo])*(pos-float64(lo))
}
