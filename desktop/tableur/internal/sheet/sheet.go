// Package sheet porte le classeur, ses feuilles et les opérations de structure.
package sheet

import (
	"fmt"
	"strings"

	"github.com/docssuite/tableur/internal/engine"
)

// Format décrit l'apparence d'une cellule.
type Format struct {
	Bold       bool   `json:"bold,omitempty"`
	Italic     bool   `json:"italic,omitempty"`
	Color      string `json:"color,omitempty"`
	Background string `json:"background,omitempty"`
	// "", "left", "center", "right"
	Align string `json:"align,omitempty"`
}

func (f Format) IsZero() bool { return f == Format{} }

// Chart décrit un graphique posé sur une feuille. Les données ne sont pas
// recopiées : le graphique désigne des plages, et suit donc les cellules.
type Chart struct {
	ID     string   `json:"id"`
	Kind   string   `json:"kind"`
	Title  string   `json:"title"`
	Labels string   `json:"labels"`
	Series []string `json:"series"`
	Bins   int      `json:"bins,omitempty"`
}

// Sheet est une feuille du classeur.
type Sheet struct {
	Name    string            `json:"name"`
	Cells   map[string]string `json:"cells"`
	Formats map[string]Format `json:"formats"`
	Rows    int               `json:"rows"`
	Columns int               `json:"columns"`
	Charts  []Chart           `json:"charts"`
}

func NewSheet(name string) *Sheet {
	return &Sheet{
		Name:    name,
		Cells:   map[string]string{},
		Formats: map[string]Format{},
		Rows:    100,
		Columns: 26,
	}
}

// Workbook rassemble les feuilles et retient celle qui est affichée.
type Workbook struct {
	Name   string   `json:"name"`
	Sheets []*Sheet `json:"sheets"`
	Active int      `json:"active"`
}

func New() *Workbook {
	return &Workbook{
		Name:   "Classeur sans titre",
		Sheets: []*Sheet{NewSheet("Feuille1")},
	}
}

// Current rend la feuille affichée, en corrigeant un index devenu invalide.
func (w *Workbook) Current() *Sheet {
	if len(w.Sheets) == 0 {
		w.Sheets = []*Sheet{NewSheet("Feuille1")}
	}
	if w.Active < 0 || w.Active >= len(w.Sheets) {
		w.Active = 0
	}
	return w.Sheets[w.Active]
}

func (w *Workbook) ByName(name string) *Sheet {
	for _, s := range w.Sheets {
		if strings.EqualFold(s.Name, name) {
			return s
		}
	}
	return nil
}

/*
EngineCells compose la vue que le moteur de formules consulte.

Les cellules de la feuille affichée y figurent sous leur nom court, et toutes
les cellules du classeur sous un nom qualifié « Feuille!A1 ». Une formule peut
ainsi désigner indifféremment sa propre feuille ou une autre.
*/
func (w *Workbook) EngineCells() engine.Cells {
	out := engine.Cells{}
	for _, s := range w.Sheets {
		for ref, raw := range s.Cells {
			out[engine.Qualify(s.Name, ref)] = raw
		}
	}
	for ref, raw := range w.Current().Cells {
		out[ref] = raw
	}
	return out
}

// AddSheet ajoute une feuille après celle affichée et l'active.
func (w *Workbook) AddSheet() *Sheet {
	s := NewSheet(w.freeName())
	at := w.Active + 1
	if at > len(w.Sheets) {
		at = len(w.Sheets)
	}
	w.Sheets = append(w.Sheets[:at], append([]*Sheet{s}, w.Sheets[at:]...)...)
	w.Active = at
	return s
}

func (w *Workbook) freeName() string {
	for i := len(w.Sheets) + 1; ; i++ {
		candidate := fmt.Sprintf("Feuille%d", i)
		if w.ByName(candidate) == nil {
			return candidate
		}
	}
}

// RemoveSheet supprime une feuille ; la dernière ne peut pas l'être.
func (w *Workbook) RemoveSheet(index int) error {
	if len(w.Sheets) <= 1 {
		return fmt.Errorf("un classeur garde au moins une feuille")
	}
	if index < 0 || index >= len(w.Sheets) {
		return fmt.Errorf("cette feuille n'existe pas")
	}
	w.Sheets = append(w.Sheets[:index], w.Sheets[index+1:]...)
	if w.Active >= len(w.Sheets) {
		w.Active = len(w.Sheets) - 1
	}
	return nil
}

/*
RenameSheet change le nom d'une feuille et réécrit les formules qui la
désignent. Sans cette reprise, « =Feuille2!A1 » pointerait dans le vide dès
le premier renommage.
*/
func (w *Workbook) RenameSheet(index int, name string) error {
	if index < 0 || index >= len(w.Sheets) {
		return fmt.Errorf("cette feuille n'existe pas")
	}
	clean := strings.TrimSpace(name)
	if clean == "" {
		return fmt.Errorf("le nom ne peut pas être vide")
	}
	if strings.ContainsAny(clean, "!'\"[]") {
		return fmt.Errorf("le nom ne peut pas contenir ! ' \" [ ]")
	}
	if len([]rune(clean)) > 31 {
		return fmt.Errorf("le nom est limité à 31 caractères")
	}
	if other := w.ByName(clean); other != nil && other != w.Sheets[index] {
		return fmt.Errorf("une feuille porte déjà ce nom")
	}
	old := w.Sheets[index].Name
	w.Sheets[index].Name = clean
	if old != clean {
		for _, s := range w.Sheets {
			for ref, raw := range s.Cells {
				s.Cells[ref] = renameInFormula(raw, old, clean)
			}
			for i, chart := range s.Charts {
				s.Charts[i].Labels = renameInRange(chart.Labels, old, clean)
				for j, series := range chart.Series {
					s.Charts[i].Series[j] = renameInRange(series, old, clean)
				}
			}
		}
	}
	return nil
}

// MoveSheet déplace une feuille dans la barre d'onglets.
func (w *Workbook) MoveSheet(from, to int) error {
	if from < 0 || from >= len(w.Sheets) || to < 0 || to >= len(w.Sheets) {
		return fmt.Errorf("position invalide")
	}
	s := w.Sheets[from]
	w.Sheets = append(w.Sheets[:from], w.Sheets[from+1:]...)
	w.Sheets = append(w.Sheets[:to], append([]*Sheet{s}, w.Sheets[to:]...)...)
	w.Active = to
	return nil
}

// quoted rend le nom tel qu'il doit apparaître dans une formule.
func quoted(name string) string {
	if strings.IndexFunc(name, func(r rune) bool {
		return !(r == '_' || r >= 'a' && r <= 'z' || r >= 'A' && r <= 'Z' || r >= '0' && r <= '9')
	}) >= 0 {
		return "'" + name + "'"
	}
	return name
}

func renameInFormula(formula, old, now string) string {
	if !strings.HasPrefix(formula, "=") {
		return formula
	}
	return replaceSheetToken(formula, old, now)
}

func renameInRange(spec, old, now string) string {
	if spec == "" || !strings.Contains(spec, "!") {
		return spec
	}
	return replaceSheetToken(spec, old, now)
}

// replaceSheetToken ne remplace que ce qui précède un « ! » : un nom de
// feuille qui apparaîtrait dans un libellé reste intact.
func replaceSheetToken(text, old, now string) string {
	out := text
	for _, form := range []string{quoted(old) + "!", old + "!", "'" + old + "'!"} {
		out = strings.ReplaceAll(out, form, quoted(now)+"!")
	}
	return out
}

// Set écrit une cellule ; une saisie vide efface la case.
func (s *Sheet) Set(ref, raw string) {
	if raw == "" {
		delete(s.Cells, ref)
		return
	}
	s.Cells[ref] = raw
}

// SetFormat applique une mise en forme ; un format nul l'efface.
func (s *Sheet) SetFormat(ref string, f Format) {
	if f.IsZero() {
		delete(s.Formats, ref)
		return
	}
	s.Formats[ref] = f
}

// Extent rend la dernière ligne et la dernière colonne réellement occupées.
func (s *Sheet) Extent() (lastRow, lastCol int) {
	lastRow, lastCol = -1, -1
	consider := func(ref string) {
		if r, c, ok := engine.SplitRef(ref); ok {
			if r > lastRow {
				lastRow = r
			}
			if c > lastCol {
				lastCol = c
			}
		}
	}
	for ref := range s.Cells {
		consider(ref)
	}
	for ref, f := range s.Formats {
		if !f.IsZero() {
			consider(ref)
		}
	}
	return lastRow, lastCol
}
