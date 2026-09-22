package sheet

import (
	"sort"
	"strings"

	"github.com/docssuite/tableur/internal/engine"
)

// Les opérations de structure ne déplacent pas seulement des cellules : les
// formules qui les désignent doivent suivre, sinon `=SOMME(A1:A5)` continuerait
// de pointer les anciennes lignes après une insertion.

func (b *Book) InsertRow(at int)    { b.shift(at, 1, -1, 0); b.Rows++ }
func (b *Book) DeleteRow(at int)    { b.shift(at, -1, -1, 0) }
func (b *Book) InsertColumn(at int) { b.shift(-1, 0, at, 1); b.Columns++ }
func (b *Book) DeleteColumn(at int) { b.shift(-1, 0, at, -1) }

func (b *Book) shift(rowAt, rowDelta, colAt, colDelta int) {
	destination := func(key string) (string, bool) {
		row, col, ok := engine.SplitRef(key)
		if !ok {
			return "", false
		}
		// Une suppression efface la ligne ou la colonne visée.
		if rowDelta < 0 && rowAt >= 0 && row == rowAt {
			return "", false
		}
		if colDelta < 0 && colAt >= 0 && col == colAt {
			return "", false
		}
		newRow, newCol := row, col
		if rowAt >= 0 && row >= rowAt {
			newRow = row + rowDelta
		}
		if colAt >= 0 && col >= colAt {
			newCol = col + colDelta
		}
		if newRow < 0 || newCol < 0 {
			return "", false
		}
		return engine.CellKey(newRow, newCol), true
	}

	cells := make(map[string]string, len(b.Cells))
	for key, value := range b.Cells {
		target, ok := destination(key)
		if !ok {
			continue
		}
		cells[target] = AdjustFormula(value, rowAt, rowDelta, colAt, colDelta)
	}
	formats := make(map[string]Format, len(b.Formats))
	for key, value := range b.Formats {
		if target, ok := destination(key); ok {
			formats[target] = value
		}
	}
	b.Cells, b.Formats = cells, formats
}

// Sort trie un bloc de lignes sur une colonne. Le contenu brut est déplacé en
// bloc : chaque ligne garde ses cellules côte à côte.
func (b *Book) Sort(column, firstRow, lastRow int, ascending bool) {
	if lastRow <= firstRow {
		return
	}
	cells := b.EngineCells()
	order := make([]int, 0, lastRow-firstRow+1)
	for r := firstRow; r <= lastRow; r++ {
		order = append(order, r)
	}
	keyOf := func(row int) (int, float64, string) {
		return sortKey(cells, engine.CellKey(row, column))
	}
	sort.SliceStable(order, func(i, j int) bool {
		ai, af, as := keyOf(order[i])
		bi, bf, bs := keyOf(order[j])
		if ai != bi {
			return ai < bi
		}
		if af != bf {
			return af < bf
		}
		return as < bs
	})
	if !ascending {
		for i, j := 0, len(order)-1; i < j; i, j = i+1, j-1 {
			order[i], order[j] = order[j], order[i]
		}
	}

	newCells := make(map[string]string, len(b.Cells))
	for k, v := range b.Cells {
		newCells[k] = v
	}
	newFormats := make(map[string]Format, len(b.Formats))
	for k, v := range b.Formats {
		newFormats[k] = v
	}
	for offset, sourceRow := range order {
		targetRow := firstRow + offset
		for c := 0; c < b.Columns; c++ {
			from := engine.CellKey(sourceRow, c)
			to := engine.CellKey(targetRow, c)
			if v, ok := b.Cells[from]; ok {
				newCells[to] = v
			} else {
				delete(newCells, to)
			}
			if f, ok := b.Formats[from]; ok {
				newFormats[to] = f
			} else {
				delete(newFormats, to)
			}
		}
	}
	b.Cells, b.Formats = newCells, newFormats
}

// sortKey : les nombres passent avant le texte, les cases vides en dernier.
func sortKey(cells engine.Cells, ref string) (int, float64, string) {
	shown := engine.Display(ref, cells)
	if strings.TrimSpace(shown) == "" {
		return 2, 0, ""
	}
	if v := engine.Literal(shown); v.Kind == engine.KindNum {
		return 0, v.Num, ""
	}
	return 1, 0, strings.ToLower(shown)
}

// AdjustFormula décale les références d'une formule. Le texte entre guillemets
// est laissé intact : « A1 » dans un libellé n'est pas une référence.
func AdjustFormula(value string, rowAt, rowDelta, colAt, colDelta int) string {
	if !strings.HasPrefix(value, "=") {
		return value
	}
	var out strings.Builder
	out.WriteByte('=')
	i := 1
	for i < len(value) {
		ch := value[i]
		if ch == '"' {
			end := strings.IndexByte(value[i+1:], '"')
			if end < 0 {
				out.WriteString(value[i:])
				break
			}
			out.WriteString(value[i : i+end+2])
			i += end + 2
			continue
		}
		if isLetter(ch) && (i == 1 || !isLetterOrDigit(value[i-1])) {
			j := i
			for j < len(value) && isLetter(value[j]) {
				j++
			}
			lettersEnd := j
			for j < len(value) && isDigit(value[j]) {
				j++
			}
			// Un nom suivi d'une parenthèse est une fonction, pas une référence.
			isReference := lettersEnd > i && j > lettersEnd && (j >= len(value) || value[j] != '(')
			if isReference {
				out.WriteString(moveReference(value[i:j], rowAt, rowDelta, colAt, colDelta))
				i = j
				continue
			}
		}
		out.WriteByte(ch)
		i++
	}
	return out.String()
}

func moveReference(ref string, rowAt, rowDelta, colAt, colDelta int) string {
	row, col, ok := engine.SplitRef(strings.ToUpper(ref))
	if !ok {
		return ref
	}
	if rowDelta < 0 && rowAt >= 0 && row == rowAt {
		return "#REF!"
	}
	if colDelta < 0 && colAt >= 0 && col == colAt {
		return "#REF!"
	}
	newRow, newCol := row, col
	if rowAt >= 0 && row >= rowAt {
		newRow = row + rowDelta
	}
	if colAt >= 0 && col >= colAt {
		newCol = col + colDelta
	}
	if newRow < 0 || newCol < 0 {
		return "#REF!"
	}
	return engine.CellKey(newRow, newCol)
}

func isDigit(c byte) bool  { return c >= '0' && c <= '9' }
func isLetter(c byte) bool { return c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z' }
func isLetterOrDigit(c byte) bool {
	return isLetter(c) || isDigit(c)
}
