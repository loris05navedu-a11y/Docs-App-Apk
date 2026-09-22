// Package engine évalue les formules du tableur.
//
// Les valeurs sont typées — nombre, texte, booléen, case vide, erreur. Sans
// cette distinction, ni les fonctions de texte, ni les recherches, ni les
// dates ne seraient possibles, et une erreur se confondrait avec un résultat.
package engine

import (
	"math"
	"strconv"
	"strings"
)

// Kind désigne la nature d'une valeur.
type Kind int

const (
	KindBlank Kind = iota
	KindNum
	KindText
	KindBool
	KindErr
)

// Value est une valeur de cellule ou un résultat intermédiaire.
type Value struct {
	Kind Kind
	Num  float64
	Text string
	Bool bool
}

// Erreurs nommées, telles qu'un tableur les affiche.
var (
	Blank    = Value{Kind: KindBlank}
	ErrValue = Value{Kind: KindErr, Text: "#VALEUR!"}
	ErrDiv0  = Value{Kind: KindErr, Text: "#DIV/0!"}
	ErrNA    = Value{Kind: KindErr, Text: "#N/A"}
	ErrName  = Value{Kind: KindErr, Text: "#NOM?"}
	ErrNum   = Value{Kind: KindErr, Text: "#NOMBRE!"}
	ErrRef   = Value{Kind: KindErr, Text: "#REF!"}
	ErrCycle = Value{Kind: KindErr, Text: "#CYCLE"}
	ErrParse = Value{Kind: KindErr, Text: "#ERREUR"}
)

// Num construit une valeur numérique, en repoussant les résultats non finis.
func Number(v float64) Value {
	if math.IsNaN(v) || math.IsInf(v, 0) {
		return ErrNum
	}
	return Value{Kind: KindNum, Num: v}
}

func Text(s string) Value  { return Value{Kind: KindText, Text: s} }
func Bool(b bool) Value    { return Value{Kind: KindBool, Bool: b} }
func (v Value) IsErr() bool { return v.Kind == KindErr }

// Literal lit une saisie brute de cellule : nombre, booléen, sinon texte.
func Literal(raw string) Value {
	if raw == "" {
		return Blank
	}
	compact := strings.ReplaceAll(strings.ReplaceAll(raw, " ", ""), ",", ".")
	if n, err := strconv.ParseFloat(compact, 64); err == nil {
		return Number(n)
	}
	switch strings.ToUpper(strings.TrimSpace(raw)) {
	case "VRAI", "TRUE":
		return Bool(true)
	case "FAUX", "FALSE":
		return Bool(false)
	}
	return Text(raw)
}

// AsNumber convertit si la valeur représente un nombre ; ok vaut faux sinon.
func (v Value) AsNumber() (float64, bool) {
	switch v.Kind {
	case KindNum:
		return v.Num, true
	case KindBool:
		if v.Bool {
			return 1, true
		}
		return 0, true
	case KindBlank:
		return 0, true
	case KindText:
		compact := strings.ReplaceAll(strings.ReplaceAll(v.Text, " ", ""), ",", ".")
		n, err := strconv.ParseFloat(compact, 64)
		return n, err == nil
	}
	return 0, false
}

// StrictNumber n'accepte que ce qui est vraiment un nombre : un texte qui
// ressemble à un nombre ne compte pas, exactement comme dans SOMME.
func (v Value) StrictNumber() (float64, bool) {
	switch v.Kind {
	case KindNum:
		return v.Num, true
	case KindBool:
		if v.Bool {
			return 1, true
		}
		return 0, true
	}
	return 0, false
}

func (v Value) AsText() string {
	switch v.Kind {
	case KindNum:
		return FormatNumber(v.Num)
	case KindText:
		return v.Text
	case KindBool:
		if v.Bool {
			return "VRAI"
		}
		return "FAUX"
	case KindErr:
		return v.Text
	}
	return ""
}

func (v Value) AsBool() bool {
	switch v.Kind {
	case KindBool:
		return v.Bool
	case KindNum:
		return v.Num != 0
	case KindText:
		u := strings.ToUpper(strings.TrimSpace(v.Text))
		return u == "VRAI" || u == "TRUE"
	}
	return false
}

// FormatNumber rend un nombre sans zéros inutiles.
func FormatNumber(v float64) string {
	if math.IsNaN(v) || math.IsInf(v, 0) {
		return "#ERREUR"
	}
	if math.Abs(v) < 1e15 && math.Abs(v-math.Round(v)) < 1e-9 {
		return strconv.FormatInt(int64(math.Round(v)), 10)
	}
	s := strconv.FormatFloat(v, 'f', 4, 64)
	s = strings.TrimRight(s, "0")
	return strings.TrimRight(s, ".")
}
