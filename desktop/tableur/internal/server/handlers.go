package server

import (
	"fmt"
	"net/http"
	"strings"

	"github.com/docssuite/tableur/internal/chart"
	"github.com/docssuite/tableur/internal/engine"
	"github.com/docssuite/tableur/internal/sheet"
)

// ------------------------------------------------------------- feuilles

func (s *Server) handleSheet(w http.ResponseWriter, r *http.Request) {
	var body struct {
		Action string `json:"action"`
		Index  int    `json:"index"`
		To     int    `json:"to"`
		Name   string `json:"name"`
	}
	if err := decode(r, &body); err != nil {
		fail(w, "requête illisible")
		return
	}
	s.mu.Lock()
	defer s.mu.Unlock()

	switch body.Action {
	case "select":
		if body.Index < 0 || body.Index >= len(s.book.Sheets) {
			fail(w, "cette feuille n'existe pas")
			return
		}
		s.book.Active = body.Index
	case "add":
		s.book.AddSheet()
		s.modified = true
	case "duplicate":
		if body.Index < 0 || body.Index >= len(s.book.Sheets) {
			fail(w, "cette feuille n'existe pas")
			return
		}
		s.duplicate(body.Index)
		s.modified = true
	case "remove":
		if err := s.book.RemoveSheet(body.Index); err != nil {
			fail(w, err.Error())
			return
		}
		s.modified = true
	case "rename":
		if err := s.book.RenameSheet(body.Index, body.Name); err != nil {
			fail(w, err.Error())
			return
		}
		s.modified = true
	case "move":
		if err := s.book.MoveSheet(body.Index, body.To); err != nil {
			fail(w, err.Error())
			return
		}
		s.modified = true
	default:
		fail(w, "action inconnue : "+body.Action)
		return
	}
	s.writeState(w)
}

// duplicate recopie une feuille en entier, graphiques compris.
func (s *Server) duplicate(index int) {
	source := s.book.Sheets[index]
	copySheet := sheet.NewSheet(s.freeCopyName(source.Name))
	copySheet.Rows, copySheet.Columns = source.Rows, source.Columns
	for ref, raw := range source.Cells {
		copySheet.Cells[ref] = raw
	}
	for ref, f := range source.Formats {
		copySheet.Formats[ref] = f
	}
	for _, c := range source.Charts {
		clone := c
		clone.ID = newChartID()
		clone.Series = append([]string(nil), c.Series...)
		copySheet.Charts = append(copySheet.Charts, clone)
	}
	at := index + 1
	s.book.Sheets = append(s.book.Sheets[:at], append([]*sheet.Sheet{copySheet}, s.book.Sheets[at:]...)...)
	s.book.Active = at
}

func (s *Server) freeCopyName(base string) string {
	for i := 2; ; i++ {
		candidate := fmt.Sprintf("%s (%d)", base, i)
		if len([]rune(candidate)) > 31 {
			candidate = fmt.Sprintf("Feuille%d", len(s.book.Sheets)+i)
		}
		if s.book.ByName(candidate) == nil {
			return candidate
		}
	}
}

// ---------------------------------------------------------- graphiques

func (s *Server) handleChartKinds(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, chart.Kinds)
}

func (s *Server) handleChart(w http.ResponseWriter, r *http.Request) {
	var body struct {
		Action string   `json:"action"`
		ID     string   `json:"id"`
		Kind   string   `json:"kind"`
		Title  string   `json:"title"`
		Labels string   `json:"labels"`
		Series []string `json:"series"`
		Bins   int      `json:"bins"`
	}
	if err := decode(r, &body); err != nil {
		fail(w, "requête illisible")
		return
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	current := s.book.Current()

	switch body.Action {
	case "add", "update":
		series := cleanRanges(body.Series)
		if len(series) == 0 {
			fail(w, "indiquez au moins une plage de valeurs, par exemple B2:B10")
			return
		}
		if !knownKind(body.Kind) {
			fail(w, "type de graphique inconnu")
			return
		}
		labels := strings.TrimSpace(strings.ToUpper(body.Labels))
		if labels != "" && len(engine.ExpandRange(labels)) == 0 {
			fail(w, "la plage des libellés n'est pas valide")
			return
		}
		definition := sheet.Chart{
			ID:     body.ID,
			Kind:   body.Kind,
			Title:  strings.TrimSpace(body.Title),
			Labels: labels,
			Series: series,
			Bins:   body.Bins,
		}
		if body.Action == "add" {
			definition.ID = newChartID()
			current.Charts = append(current.Charts, definition)
		} else {
			found := false
			for i, c := range current.Charts {
				if c.ID == body.ID {
					current.Charts[i] = definition
					found = true
					break
				}
			}
			if !found {
				fail(w, "ce graphique n'existe plus")
				return
			}
		}
		s.modified = true

	case "remove":
		kept := current.Charts[:0]
		for _, c := range current.Charts {
			if c.ID != body.ID {
				kept = append(kept, c)
			}
		}
		current.Charts = kept
		s.modified = true

	default:
		fail(w, "action inconnue : "+body.Action)
		return
	}
	s.writeState(w)
}

// cleanRanges ne retient que les plages réellement interprétables.
func cleanRanges(specs []string) []string {
	var out []string
	for _, raw := range specs {
		spec := strings.ToUpper(strings.ReplaceAll(strings.TrimSpace(raw), " ", ""))
		if spec == "" {
			continue
		}
		// Une plage peut désigner une autre feuille : on ne valide alors que
		// la partie cellules.
		check := spec
		if at := strings.LastIndex(spec, "!"); at >= 0 {
			check = spec[at+1:]
		}
		if len(engine.ExpandRange(check)) == 0 {
			continue
		}
		out = append(out, spec)
	}
	return out
}

func knownKind(id string) bool {
	for _, k := range chart.Kinds {
		if k.ID == id {
			return true
		}
	}
	return false
}

var chartCounter int

func newChartID() string {
	chartCounter++
	return fmt.Sprintf("g%d", chartCounter)
}
