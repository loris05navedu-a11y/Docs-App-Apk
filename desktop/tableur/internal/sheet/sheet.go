// Package sheet porte le classeur et les opérations de structure.
package sheet

import (
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

// Book est un classeur : les cellules, leurs formats et les dimensions.
type Book struct {
	Name    string            `json:"name"`
	Cells   map[string]string `json:"cells"`
	Formats map[string]Format `json:"formats"`
	Rows    int               `json:"rows"`
	Columns int               `json:"columns"`
}

func New() *Book {
	return &Book{
		Name:    "Classeur sans titre",
		Cells:   map[string]string{},
		Formats: map[string]Format{},
		Rows:    100,
		Columns: 26,
	}
}

// EngineCells expose le contenu au moteur de formules.
func (b *Book) EngineCells() engine.Cells {
	out := make(engine.Cells, len(b.Cells))
	for k, v := range b.Cells {
		out[k] = v
	}
	return out
}

// Set écrit une cellule ; une saisie vide efface la case.
func (b *Book) Set(ref, raw string) {
	if raw == "" {
		delete(b.Cells, ref)
		return
	}
	b.Cells[ref] = raw
}

// SetFormat applique une mise en forme ; un format nul l'efface.
func (b *Book) SetFormat(ref string, f Format) {
	if f.IsZero() {
		delete(b.Formats, ref)
		return
	}
	b.Formats[ref] = f
}

// Extent rend la dernière ligne et la dernière colonne réellement occupées.
func (b *Book) Extent() (lastRow, lastCol int) {
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
	for ref := range b.Cells {
		consider(ref)
	}
	for ref, f := range b.Formats {
		if !f.IsZero() {
			consider(ref)
		}
	}
	return lastRow, lastCol
}
