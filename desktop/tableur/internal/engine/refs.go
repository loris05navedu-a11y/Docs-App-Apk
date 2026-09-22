package engine

import (
	"strconv"
	"strings"
)

// ColumnLabel : 0 -> A, 25 -> Z, 26 -> AA…
func ColumnLabel(index int) string {
	i := index
	var sb []byte
	for {
		sb = append([]byte{byte('A' + i%26)}, sb...)
		i = i/26 - 1
		if i < 0 {
			break
		}
	}
	return string(sb)
}

// ColumnIndex : "A" -> 0, "AA" -> 26. Rend -1 si l'étiquette est vide.
func ColumnIndex(label string) int {
	n := 0
	for _, c := range strings.ToUpper(label) {
		if c < 'A' || c > 'Z' {
			break
		}
		n = n*26 + int(c-'A') + 1
	}
	return n - 1
}

// CellKey compose la référence A1 d'une position.
func CellKey(row, col int) string {
	return ColumnLabel(col) + strconv.Itoa(row+1)
}

// SplitRef décompose "B3" en (ligne 2, colonne 1).
func SplitRef(ref string) (row, col int, ok bool) {
	letters := ""
	digits := ""
	for _, c := range ref {
		if c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z' {
			if digits != "" {
				return 0, 0, false
			}
			letters += string(c)
		} else if c >= '0' && c <= '9' {
			digits += string(c)
		} else {
			return 0, 0, false
		}
	}
	if letters == "" || digits == "" {
		return 0, 0, false
	}
	n, err := strconv.Atoi(digits)
	if err != nil || n < 1 {
		return 0, 0, false
	}
	return n - 1, ColumnIndex(letters), true
}

// IsCellRef indique si le texte a la forme d'une référence A1.
func IsCellRef(text string) bool {
	_, _, ok := SplitRef(strings.ToUpper(text))
	return ok
}

// ExpandRange déplie "A1:B3" en la liste des cellules, ligne par ligne.
func ExpandRange(r string) []string {
	parts := strings.Split(strings.ReplaceAll(strings.ToUpper(r), " ", ""), ":")
	if len(parts) == 1 {
		if IsCellRef(parts[0]) {
			return []string{parts[0]}
		}
		return nil
	}
	if len(parts) != 2 {
		return nil
	}
	r1, c1, ok1 := SplitRef(parts[0])
	r2, c2, ok2 := SplitRef(parts[1])
	if !ok1 || !ok2 {
		return nil
	}
	if r1 > r2 {
		r1, r2 = r2, r1
	}
	if c1 > c2 {
		c1, c2 = c2, c1
	}
	out := make([]string, 0, (r2-r1+1)*(c2-c1+1))
	for r := r1; r <= r2; r++ {
		for c := c1; c <= c2; c++ {
			out = append(out, CellKey(r, c))
		}
	}
	return out
}
