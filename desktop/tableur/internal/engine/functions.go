package engine

import (
	"math"
	"math/rand"
	"regexp"
	"sort"
	"strings"
	"time"
	"unicode"
)

// errorTransparent : ces fonctions doivent voir l'erreur plutôt que la subir.
var errorTransparent = map[string]bool{
	"SIERREUR": true, "IFERROR": true,
	"ESTERREUR": true, "ISERROR": true,
	"ESTVIDE": true, "ISBLANK": true,
}

// apply aiguille un appel de fonction. Une erreur en entrée est une erreur en
// sortie, sauf pour les fonctions qui ont justement pour rôle de l'examiner.
func apply(name string, args []Arg) Value {
	if !errorTransparent[name] {
		for _, v := range values(args) {
			if v.IsErr() {
				return v
			}
		}
	}
	if f, ok := mathFns[name]; ok {
		return f(name, args)
	}
	if f, ok := statFns[name]; ok {
		return f(name, args)
	}
	if f, ok := condFns[name]; ok {
		return f(name, args)
	}
	if f, ok := logicFns[name]; ok {
		return f(name, args)
	}
	if f, ok := textFns[name]; ok {
		return f(name, args)
	}
	if f, ok := lookupFns[name]; ok {
		return f(name, args)
	}
	if f, ok := dateFns[name]; ok {
		return f(name, args)
	}
	if f, ok := financeFns[name]; ok {
		return f(name, args)
	}
	return ErrName
}

type fn func(name string, args []Arg) Value

func register(target map[string]fn, f fn, names ...string) {
	for _, n := range names {
		target[n] = f
	}
}

// num applique un calcul au premier argument s'il est numérique.
func num(args []Arg, i int, f func(float64) Value) Value {
	v, ok := argValue(args, i).AsNumber()
	if !ok {
		return ErrValue
	}
	return f(v)
}

// ------------------------------------------------------------------ maths

var mathFns = map[string]fn{}

func init() {
	register(mathFns, fnMath,
		"SOMME", "SUM", "PRODUIT", "PRODUCT", "ABS", "ARRONDI", "ROUND",
		"ARRONDI.SUP", "ROUNDUP", "ARRONDI.INF", "ROUNDDOWN", "ENT", "INT",
		"TRONQUE", "TRUNC", "MOD", "RACINE", "SQRT", "PUISSANCE", "POWER", "POW",
		"EXP", "LN", "LOG", "LOG10", "SIGNE", "SIGN", "PLAFOND", "CEILING",
		"PLANCHER", "FLOOR", "ARRONDI.AU.MULTIPLE", "MROUND", "PAIR", "EVEN",
		"IMPAIR", "ODD", "FACT", "PGCD", "GCD", "PPCM", "LCM", "SOMME.CARRES",
		"SUMSQ", "SOMMEPROD", "SUMPRODUCT", "ALEA", "RAND", "ALEA.ENTRE.BORNES",
		"RANDBETWEEN", "DEGRES", "DEGREES", "RADIANS", "SIN", "COS", "TAN",
		"ASIN", "ACOS", "ATAN", "ATAN2", "PI")
}

func fnMath(name string, args []Arg) Value {
	ns := numbers(args)
	switch name {
	case "SOMME", "SUM":
		return Number(sum(ns))
	case "PRODUIT", "PRODUCT":
		p := 1.0
		for _, n := range ns {
			p *= n
		}
		return Number(p)
	case "ABS":
		return num(args, 0, func(v float64) Value { return Number(math.Abs(v)) })
	case "ARRONDI", "ROUND":
		d, _ := optionalNumber(args, 1)
		f := math.Pow(10, d)
		return num(args, 0, func(v float64) Value { return Number(math.Round(v*f) / f) })
	case "ARRONDI.SUP", "ROUNDUP":
		d, _ := optionalNumber(args, 1)
		f := math.Pow(10, d)
		return num(args, 0, func(v float64) Value {
			if v < 0 {
				return Number(math.Floor(v*f) / f)
			}
			return Number(math.Ceil(v*f) / f)
		})
	case "ARRONDI.INF", "ROUNDDOWN":
		d, _ := optionalNumber(args, 1)
		f := math.Pow(10, d)
		return num(args, 0, func(v float64) Value {
			if v < 0 {
				return Number(math.Ceil(v*f) / f)
			}
			return Number(math.Floor(v*f) / f)
		})
	case "ENT", "INT":
		return num(args, 0, func(v float64) Value { return Number(math.Floor(v)) })
	case "TRONQUE", "TRUNC":
		d, _ := optionalNumber(args, 1)
		f := math.Pow(10, d)
		return num(args, 0, func(v float64) Value {
			if v < 0 {
				return Number(math.Ceil(v*f) / f)
			}
			return Number(math.Floor(v*f) / f)
		})
	case "MOD":
		d, ok := argValue(args, 1).AsNumber()
		if !ok {
			return ErrValue
		}
		if d == 0 {
			return ErrDiv0
		}
		// Le reste suit le signe du diviseur, comme dans un tableur.
		return num(args, 0, func(v float64) Value { return Number(math.Mod(math.Mod(v, d)+d, d)) })
	case "RACINE", "SQRT":
		return num(args, 0, func(v float64) Value {
			if v < 0 {
				return ErrNum
			}
			return Number(math.Sqrt(v))
		})
	case "PUISSANCE", "POWER", "POW":
		e, ok := argValue(args, 1).AsNumber()
		if !ok {
			return ErrValue
		}
		return num(args, 0, func(v float64) Value { return Number(math.Pow(v, e)) })
	case "EXP":
		return num(args, 0, func(v float64) Value { return Number(math.Exp(v)) })
	case "LN":
		return num(args, 0, func(v float64) Value {
			if v <= 0 {
				return ErrNum
			}
			return Number(math.Log(v))
		})
	case "LOG10":
		return num(args, 0, func(v float64) Value {
			if v <= 0 {
				return ErrNum
			}
			return Number(math.Log10(v))
		})
	case "LOG":
		base, hasBase := optionalNumber(args, 1)
		return num(args, 0, func(v float64) Value {
			if v <= 0 {
				return ErrNum
			}
			if !hasBase {
				return Number(math.Log10(v))
			}
			return Number(math.Log(v) / math.Log(base))
		})
	case "SIGNE", "SIGN":
		return num(args, 0, func(v float64) Value {
			switch {
			case v > 0:
				return Number(1)
			case v < 0:
				return Number(-1)
			}
			return Number(0)
		})
	case "PLAFOND", "CEILING":
		step, ok := optionalNumber(args, 1)
		if !ok {
			step = 1
		}
		if step == 0 {
			return ErrDiv0
		}
		return num(args, 0, func(v float64) Value { return Number(math.Ceil(v/step) * step) })
	case "PLANCHER", "FLOOR":
		step, ok := optionalNumber(args, 1)
		if !ok {
			step = 1
		}
		if step == 0 {
			return ErrDiv0
		}
		return num(args, 0, func(v float64) Value { return Number(math.Floor(v/step) * step) })
	case "ARRONDI.AU.MULTIPLE", "MROUND":
		step, ok := optionalNumber(args, 1)
		if !ok {
			step = 1
		}
		if step == 0 {
			return Number(0)
		}
		return num(args, 0, func(v float64) Value { return Number(math.Round(v/step) * step) })
	case "PAIR", "EVEN":
		return num(args, 0, func(v float64) Value {
			up := math.Ceil(math.Abs(v)/2) * 2
			if v < 0 {
				return Number(-up)
			}
			return Number(up)
		})
	case "IMPAIR", "ODD":
		return num(args, 0, func(v float64) Value {
			up := math.Ceil(math.Abs(v))
			if math.Mod(up, 2) == 0 {
				up++
			}
			if up == 0 {
				up = 1
			}
			if v < 0 {
				return Number(-up)
			}
			return Number(up)
		})
	case "FACT":
		return num(args, 0, func(v float64) Value {
			n := int(math.Floor(v))
			if n < 0 || n > 170 {
				return ErrNum
			}
			r := 1.0
			for i := 2; i <= n; i++ {
				r *= float64(i)
			}
			return Number(r)
		})
	case "PGCD", "GCD":
		var g int64
		for _, n := range ns {
			g = gcd(g, int64(math.Abs(n)))
		}
		return Number(float64(g))
	case "PPCM", "LCM":
		var l int64 = 1
		for _, n := range ns {
			b := int64(math.Abs(n))
			if l == 0 || b == 0 {
				l = 0
				continue
			}
			l = l / gcd(l, b) * b
		}
		return Number(float64(l))
	case "SOMME.CARRES", "SUMSQ":
		t := 0.0
		for _, n := range ns {
			t += n * n
		}
		return Number(t)
	case "SOMMEPROD", "SUMPRODUCT":
		return sumProduct(args)
	case "ALEA", "RAND":
		return Number(rand.Float64())
	case "ALEA.ENTRE.BORNES", "RANDBETWEEN":
		lo, ok1 := argValue(args, 0).AsNumber()
		hi, ok2 := argValue(args, 1).AsNumber()
		if !ok1 || !ok2 {
			return ErrValue
		}
		if hi < lo {
			return ErrNum
		}
		return Number(math.Floor(lo) + float64(rand.Int63n(int64(hi-lo)+1)))
	case "DEGRES", "DEGREES":
		return num(args, 0, func(v float64) Value { return Number(v * 180 / math.Pi) })
	case "RADIANS":
		return num(args, 0, func(v float64) Value { return Number(v * math.Pi / 180) })
	case "SIN":
		return num(args, 0, func(v float64) Value { return Number(math.Sin(v)) })
	case "COS":
		return num(args, 0, func(v float64) Value { return Number(math.Cos(v)) })
	case "TAN":
		return num(args, 0, func(v float64) Value { return Number(math.Tan(v)) })
	case "ASIN":
		return num(args, 0, func(v float64) Value {
			if math.Abs(v) > 1 {
				return ErrNum
			}
			return Number(math.Asin(v))
		})
	case "ACOS":
		return num(args, 0, func(v float64) Value {
			if math.Abs(v) > 1 {
				return ErrNum
			}
			return Number(math.Acos(v))
		})
	case "ATAN":
		return num(args, 0, func(v float64) Value { return Number(math.Atan(v)) })
	case "ATAN2":
		y, ok := argValue(args, 1).AsNumber()
		if !ok {
			return ErrValue
		}
		return num(args, 0, func(v float64) Value { return Number(math.Atan2(y, v)) })
	case "PI":
		return Number(math.Pi)
	}
	return ErrName
}

func sum(ns []float64) float64 {
	t := 0.0
	for _, n := range ns {
		t += n
	}
	return t
}

func gcd(a, b int64) int64 {
	for b != 0 {
		a, b = b, a%b
	}
	return a
}

func sumProduct(args []Arg) Value {
	cols := make([][]float64, 0, len(args))
	for _, a := range args {
		var col []float64
		if a.IsRange {
			for _, row := range a.Grid {
				for _, v := range row {
					n, _ := v.StrictNumber()
					col = append(col, n)
				}
			}
		} else {
			n, _ := a.Value.StrictNumber()
			col = append(col, n)
		}
		cols = append(cols, col)
	}
	if len(cols) == 0 {
		return Number(0)
	}
	size := len(cols[0])
	for _, c := range cols {
		if len(c) < size {
			size = len(c)
		}
	}
	total := 0.0
	for i := 0; i < size; i++ {
		p := 1.0
		for _, c := range cols {
			p *= c[i]
		}
		total += p
	}
	return Number(total)
}

// ---------------------------------------------------------- statistiques

var statFns = map[string]fn{}

func init() {
	register(statFns, fnStat,
		"MOYENNE", "AVERAGE", "AVG", "MIN", "MAX", "NB", "COUNT", "NBVAL",
		"COUNTA", "NB.VIDE", "COUNTBLANK", "MEDIANE", "MEDIAN", "MODE",
		"ECARTYPE", "STDEV", "ECARTYPEP", "STDEVP", "VAR", "VARP",
		"GRANDE.VALEUR", "LARGE", "PETITE.VALEUR", "SMALL", "RANG", "RANK",
		"CENTILE", "PERCENTILE", "QUARTILE")
}

func fnStat(name string, args []Arg) Value {
	ns := numbers(args)
	switch name {
	case "MOYENNE", "AVERAGE", "AVG":
		if len(ns) == 0 {
			return ErrDiv0
		}
		return Number(sum(ns) / float64(len(ns)))
	case "MIN":
		if len(ns) == 0 {
			return Number(0)
		}
		m := ns[0]
		for _, n := range ns {
			if n < m {
				m = n
			}
		}
		return Number(m)
	case "MAX":
		if len(ns) == 0 {
			return Number(0)
		}
		m := ns[0]
		for _, n := range ns {
			if n > m {
				m = n
			}
		}
		return Number(m)
	case "NB", "COUNT":
		return Number(float64(len(ns)))
	case "NBVAL", "COUNTA":
		c := 0
		for _, v := range values(args) {
			if v.Kind != KindBlank {
				c++
			}
		}
		return Number(float64(c))
	case "NB.VIDE", "COUNTBLANK":
		c := 0
		for _, v := range values(args) {
			if v.Kind == KindBlank {
				c++
			}
		}
		return Number(float64(c))
	case "MEDIANE", "MEDIAN":
		if len(ns) == 0 {
			return ErrNum
		}
		return Number(median(sortedCopy(ns)))
	case "MODE":
		counts := map[float64]int{}
		for _, n := range ns {
			counts[n]++
		}
		best, bestCount := 0.0, 1
		for v, c := range counts {
			if c > bestCount || (c == bestCount && c > 1 && v < best) {
				best, bestCount = v, c
			}
		}
		if bestCount < 2 {
			return ErrNA
		}
		return Number(best)
	case "ECARTYPE", "STDEV":
		if len(ns) < 2 {
			return ErrDiv0
		}
		return Number(math.Sqrt(variance(ns, true)))
	case "ECARTYPEP", "STDEVP":
		if len(ns) == 0 {
			return ErrDiv0
		}
		return Number(math.Sqrt(variance(ns, false)))
	case "VAR":
		if len(ns) < 2 {
			return ErrDiv0
		}
		return Number(variance(ns, true))
	case "VARP":
		if len(ns) == 0 {
			return ErrDiv0
		}
		return Number(variance(ns, false))
	case "GRANDE.VALEUR", "LARGE", "PETITE.VALEUR", "SMALL":
		k, _ := optionalNumber(args, len(args)-1)
		pool := poolWithoutLast(args)
		s := sortedCopy(pool)
		i := int(k) - 1
		if i < 0 || i >= len(s) {
			return ErrNum
		}
		if name == "GRANDE.VALEUR" || name == "LARGE" {
			return Number(s[len(s)-1-i])
		}
		return Number(s[i])
	case "RANG", "RANK":
		target, ok := argValue(args, 0).AsNumber()
		if !ok {
			return ErrValue
		}
		pool, has := flatGrid(args, 1)
		if !has {
			return ErrValue
		}
		var nums []float64
		for _, v := range pool {
			if n, ok := v.StrictNumber(); ok {
				nums = append(nums, n)
			}
		}
		asc, _ := optionalNumber(args, 2)
		s := sortedCopy(nums)
		if asc == 0 {
			reverse(s)
		}
		for i, v := range s {
			if math.Abs(v-target) < 1e-9 {
				return Number(float64(i + 1))
			}
		}
		return ErrNA
	case "CENTILE", "PERCENTILE", "QUARTILE":
		last, _ := optionalNumber(args, len(args)-1)
		pool := poolWithoutLast(args)
		if len(pool) == 0 {
			return ErrNum
		}
		p := last
		if name == "QUARTILE" {
			p = math.Min(math.Max(last, 0), 4) / 4
		}
		return Number(quantile(sortedCopy(pool), math.Min(math.Max(p, 0), 1)))
	}
	return ErrName
}

// poolWithoutLast écarte le paramètre final (k, n, p) du lot de nombres.
func poolWithoutLast(args []Arg) []float64 {
	if len(args) == 0 {
		return nil
	}
	if len(args) > 1 {
		if p := numbers(args[:len(args)-1]); len(p) > 0 {
			return p
		}
	}
	all := numbers(args)
	if len(all) > 0 {
		return all[:len(all)-1]
	}
	return nil
}

func sortedCopy(ns []float64) []float64 {
	s := append([]float64(nil), ns...)
	sort.Float64s(s)
	return s
}

func reverse(s []float64) {
	for i, j := 0, len(s)-1; i < j; i, j = i+1, j-1 {
		s[i], s[j] = s[j], s[i]
	}
}

func median(sorted []float64) float64 {
	n := len(sorted)
	if n%2 == 1 {
		return sorted[n/2]
	}
	return (sorted[n/2-1] + sorted[n/2]) / 2
}

func variance(ns []float64, sample bool) float64 {
	mean := sum(ns) / float64(len(ns))
	t := 0.0
	for _, n := range ns {
		t += (n - mean) * (n - mean)
	}
	if sample {
		return t / float64(len(ns)-1)
	}
	return t / float64(len(ns))
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

// ------------------------------------------------------- conditionnelles

var condFns = map[string]fn{}

func init() {
	register(condFns, fnCond,
		"SOMME.SI", "SUMIF", "NB.SI", "COUNTIF", "MOYENNE.SI", "AVERAGEIF")
}

func fnCond(name string, args []Arg) Value {
	source, ok := flatGrid(args, 0)
	if !ok {
		return ErrValue
	}
	criterion := argValue(args, 1)
	var matched []int
	for i, v := range source {
		if matches(v, criterion) {
			matched = append(matched, i)
		}
	}
	target := source
	// Un troisième argument déplace le calcul sur une autre plage, alignée
	// position par position sur celle du critère.
	if alt, has := flatGrid(args, 2); has {
		target = alt
	}
	switch name {
	case "NB.SI", "COUNTIF":
		return Number(float64(len(matched)))
	case "SOMME.SI", "SUMIF":
		t := 0.0
		for _, i := range matched {
			if i < len(target) {
				n, _ := target[i].StrictNumber()
				t += n
			}
		}
		return Number(t)
	case "MOYENNE.SI", "AVERAGEIF":
		var picked []float64
		for _, i := range matched {
			if i < len(target) {
				if n, ok := target[i].StrictNumber(); ok {
					picked = append(picked, n)
				}
			}
		}
		if len(picked) == 0 {
			return ErrDiv0
		}
		return Number(sum(picked) / float64(len(picked)))
	}
	return ErrName
}

// matches interprète ">10", "<>0", "pomme", "po*"…
func matches(value, criterion Value) bool {
	raw := strings.TrimSpace(criterion.AsText())
	operator := ""
	for _, op := range []string{">=", "<=", "<>", ">", "<", "="} {
		if strings.HasPrefix(raw, op) {
			operator = op
			break
		}
	}
	operand := strings.TrimSpace(strings.TrimPrefix(raw, operator))
	target := Literal(operand)

	a, aok := value.StrictNumber()
	b, bok := target.StrictNumber()
	var c int
	if aok && bok {
		switch {
		case a < b:
			c = -1
		case a > b:
			c = 1
		}
	} else {
		c = strings.Compare(strings.ToLower(value.AsText()), strings.ToLower(target.AsText()))
	}

	switch operator {
	case ">=":
		return c >= 0
	case "<=":
		return c <= 0
	case "<>":
		return c != 0
	case ">":
		return c > 0
	case "<":
		return c < 0
	}
	if !aok && !bok && (strings.ContainsAny(operand, "*?")) {
		return wildcard(operand).MatchString(value.AsText())
	}
	return c == 0
}

func wildcard(pattern string) *regexp.Regexp {
	var sb strings.Builder
	sb.WriteString("(?i)^")
	for _, ch := range pattern {
		switch ch {
		case '*':
			sb.WriteString(".*")
		case '?':
			sb.WriteString(".")
		default:
			sb.WriteString(regexp.QuoteMeta(string(ch)))
		}
	}
	sb.WriteString("$")
	re, err := regexp.Compile(sb.String())
	if err != nil {
		return regexp.MustCompile("$^")
	}
	return re
}

// ---------------------------------------------------------------- logique

var logicFns = map[string]fn{}

func init() {
	register(logicFns, fnLogic,
		"SI", "IF", "ET", "AND", "OU", "OR", "NON", "NOT", "OUX", "XOR",
		"SIERREUR", "IFERROR", "SI.CONDITIONS", "IFS", "ESTNUM", "ISNUMBER",
		"ESTTEXTE", "ISTEXT", "ESTVIDE", "ISBLANK", "ESTERREUR", "ISERROR",
		"VRAI", "TRUE", "FAUX", "FALSE")
}

func fnLogic(name string, args []Arg) Value {
	switch name {
	case "SI", "IF":
		if argValue(args, 0).AsBool() {
			return argValue(args, 1)
		}
		if len(args) > 2 {
			return argValue(args, 2)
		}
		return Bool(false)
	case "ET", "AND":
		for _, v := range values(args) {
			if !v.AsBool() {
				return Bool(false)
			}
		}
		return Bool(true)
	case "OU", "OR":
		for _, v := range values(args) {
			if v.AsBool() {
				return Bool(true)
			}
		}
		return Bool(false)
	case "NON", "NOT":
		return Bool(!argValue(args, 0).AsBool())
	case "OUX", "XOR":
		c := 0
		for _, v := range values(args) {
			if v.AsBool() {
				c++
			}
		}
		return Bool(c%2 == 1)
	case "SIERREUR", "IFERROR":
		if argValue(args, 0).IsErr() {
			return argValue(args, 1)
		}
		return argValue(args, 0)
	case "SI.CONDITIONS", "IFS":
		vs := values(args)
		for i := 0; i+1 < len(vs); i += 2 {
			if vs[i].AsBool() {
				return vs[i+1]
			}
		}
		return ErrNA
	case "ESTNUM", "ISNUMBER":
		return Bool(argValue(args, 0).Kind == KindNum)
	case "ESTTEXTE", "ISTEXT":
		return Bool(argValue(args, 0).Kind == KindText)
	case "ESTVIDE", "ISBLANK":
		return Bool(argValue(args, 0).Kind == KindBlank)
	case "ESTERREUR", "ISERROR":
		return Bool(argValue(args, 0).IsErr())
	case "VRAI", "TRUE":
		return Bool(true)
	case "FAUX", "FALSE":
		return Bool(false)
	}
	return ErrName
}

// ------------------------------------------------------------------ texte

var textFns = map[string]fn{}

func init() {
	register(textFns, fnText,
		"CONCATENER", "CONCAT", "CONCATENATE", "GAUCHE", "LEFT", "DROITE",
		"RIGHT", "STXT", "MID", "NBCAR", "LEN", "MAJUSCULE", "UPPER",
		"MINUSCULE", "LOWER", "NOMPROPRE", "PROPER", "SUPPRESPACE", "TRIM",
		"SUBSTITUE", "SUBSTITUTE", "REMPLACER", "REPLACE", "TROUVE", "FIND",
		"CHERCHE", "SEARCH", "REPT", "JOINDRE.TEXTE", "TEXTJOIN", "CNUM",
		"VALUE", "TEXTE", "TEXT", "EXACT", "CAR", "CHAR", "CODE")
}

func fnText(name string, args []Arg) Value {
	str := func(i int) []rune { return []rune(argValue(args, i).AsText()) }
	s := func(i int) string { return argValue(args, i).AsText() }

	switch name {
	case "CONCATENER", "CONCAT", "CONCATENATE":
		var sb strings.Builder
		for _, v := range values(args) {
			sb.WriteString(v.AsText())
		}
		return Text(sb.String())
	case "GAUCHE", "LEFT":
		n, _ := optionalNumber(args, 1)
		if n < 1 {
			n = 1
		}
		r := str(0)
		if int(n) > len(r) {
			n = float64(len(r))
		}
		return Text(string(r[:int(n)]))
	case "DROITE", "RIGHT":
		n, _ := optionalNumber(args, 1)
		if n < 1 {
			n = 1
		}
		r := str(0)
		if int(n) > len(r) {
			n = float64(len(r))
		}
		return Text(string(r[len(r)-int(n):]))
	case "STXT", "MID":
		start, _ := optionalNumber(args, 1)
		length, _ := optionalNumber(args, 2)
		r := str(0)
		from := int(start) - 1
		if from < 0 {
			return ErrValue
		}
		if from > len(r) {
			return Text("")
		}
		to := from + int(length)
		if to > len(r) {
			to = len(r)
		}
		if to < from {
			to = from
		}
		return Text(string(r[from:to]))
	case "NBCAR", "LEN":
		return Number(float64(len(str(0))))
	case "MAJUSCULE", "UPPER":
		return Text(strings.ToUpper(s(0)))
	case "MINUSCULE", "LOWER":
		return Text(strings.ToLower(s(0)))
	case "NOMPROPRE", "PROPER":
		return Text(properCase(s(0)))
	case "SUPPRESPACE", "TRIM":
		return Text(strings.Join(strings.Fields(s(0)), " "))
	case "SUBSTITUE", "SUBSTITUTE":
		return Text(strings.ReplaceAll(s(0), s(1), s(2)))
	case "REMPLACER", "REPLACE":
		r := str(0)
		start, _ := optionalNumber(args, 1)
		length, _ := optionalNumber(args, 2)
		from := int(start) - 1
		if from < 0 || from > len(r) {
			return ErrValue
		}
		to := from + int(length)
		if to > len(r) {
			to = len(r)
		}
		return Text(string(r[:from]) + s(3) + string(r[to:]))
	case "TROUVE", "FIND":
		return findIn(s(1), s(0), args, false)
	case "CHERCHE", "SEARCH":
		return findIn(s(1), s(0), args, true)
	case "REPT":
		n, _ := optionalNumber(args, 1)
		if n < 0 {
			n = 0
		}
		if n > 10000 {
			n = 10000
		}
		return Text(strings.Repeat(s(0), int(n)))
	case "JOINDRE.TEXTE", "TEXTJOIN":
		sep := s(0)
		skipEmpty := argValue(args, 1).AsBool()
		var parts []string
		if len(args) > 2 {
			for _, v := range values(args[2:]) {
				t := v.AsText()
				if skipEmpty && t == "" {
					continue
				}
				parts = append(parts, t)
			}
		}
		return Text(strings.Join(parts, sep))
	case "CNUM", "VALUE":
		if n, ok := argValue(args, 0).AsNumber(); ok {
			return Number(n)
		}
		return ErrValue
	case "TEXTE", "TEXT":
		return Text(formatWith(argValue(args, 0), s(1)))
	case "EXACT":
		return Bool(s(0) == s(1))
	case "CAR", "CHAR":
		c, ok := optionalNumber(args, 0)
		if !ok || c < 1 || c > 0x10FFFF {
			return ErrValue
		}
		return Text(string(rune(int(c))))
	case "CODE":
		r := str(0)
		if len(r) == 0 {
			return ErrValue
		}
		return Number(float64(r[0]))
	}
	return ErrName
}

func findIn(haystack, needle string, args []Arg, ignoreCase bool) Value {
	from, ok := optionalNumber(args, 2)
	if !ok {
		from = 1
	}
	start := int(from) - 1
	if start < 0 {
		start = 0
	}
	r := []rune(haystack)
	if start > len(r) {
		return ErrNA
	}
	hay := string(r[start:])
	n := needle
	if ignoreCase {
		hay = strings.ToLower(hay)
		n = strings.ToLower(n)
	}
	at := strings.Index(hay, n)
	if at < 0 {
		return ErrNA
	}
	// Index compte des octets : on repasse en caractères.
	return Number(float64(start + len([]rune(hay[:at])) + 1))
}

func properCase(s string) string {
	var sb strings.Builder
	newWord := true
	for _, ch := range s {
		if unicode.IsLetter(ch) {
			if newWord {
				sb.WriteRune(unicode.ToUpper(ch))
			} else {
				sb.WriteRune(unicode.ToLower(ch))
			}
			newWord = false
		} else {
			sb.WriteRune(ch)
			newWord = true
		}
	}
	return sb.String()
}

// ------------------------------------------------------------- recherche

var lookupFns = map[string]fn{}

func init() {
	register(lookupFns, fnLookup,
		"RECHERCHEV", "VLOOKUP", "RECHERCHEH", "HLOOKUP", "INDEX", "EQUIV",
		"MATCH", "CHOISIR", "CHOOSE", "LIGNES", "ROWS", "COLONNES", "COLUMNS")
}

func fnLookup(name string, args []Arg) Value {
	switch name {
	case "RECHERCHEV", "VLOOKUP":
		grid, ok := argGrid(args, 1)
		if !ok || len(grid) == 0 {
			return ErrRef
		}
		col, _ := optionalNumber(args, 2)
		c := int(col) - 1
		if c < 0 || c >= len(grid[0]) {
			return ErrRef
		}
		approx := len(args) < 4 || argValue(args, 3).AsBool()
		first := make([]Value, len(grid))
		for i, row := range grid {
			if len(row) > 0 {
				first[i] = row[0]
			} else {
				first[i] = Blank
			}
		}
		r := findRow(first, argValue(args, 0), approx)
		if r < 0 {
			return ErrNA
		}
		if c < len(grid[r]) {
			return grid[r][c]
		}
		return ErrNA
	case "RECHERCHEH", "HLOOKUP":
		grid, ok := argGrid(args, 1)
		if !ok || len(grid) == 0 {
			return ErrRef
		}
		row, _ := optionalNumber(args, 2)
		r := int(row) - 1
		if r < 0 || r >= len(grid) {
			return ErrRef
		}
		approx := len(args) < 4 || argValue(args, 3).AsBool()
		c := findRow(grid[0], argValue(args, 0), approx)
		if c < 0 || c >= len(grid[r]) {
			return ErrNA
		}
		return grid[r][c]
	case "INDEX":
		grid, ok := argGrid(args, 0)
		if !ok {
			return ErrRef
		}
		row, _ := optionalNumber(args, 1)
		col, hasCol := optionalNumber(args, 2)
		if !hasCol && (len(grid) == 1 || (len(grid) > 0 && len(grid[0]) == 1)) {
			// Plage sur une seule ligne ou colonne : un indice suffit.
			var flat []Value
			for _, r := range grid {
				flat = append(flat, r...)
			}
			i := int(row) - 1
			if i < 0 || i >= len(flat) {
				return ErrRef
			}
			return flat[i]
		}
		c := 1.0
		if hasCol {
			c = col
		}
		ri, ci := int(row)-1, int(c)-1
		if ri < 0 || ri >= len(grid) || ci < 0 || ci >= len(grid[ri]) {
			return ErrRef
		}
		return grid[ri][ci]
	case "EQUIV", "MATCH":
		pool, ok := flatGrid(args, 1)
		if !ok {
			return ErrNA
		}
		mode, hasMode := optionalNumber(args, 2)
		if !hasMode {
			mode = 1
		}
		var at int
		if mode == 0 {
			at = -1
			for i, v := range pool {
				if matches(v, argValue(args, 0)) {
					at = i
					break
				}
			}
		} else {
			at = findRow(pool, argValue(args, 0), true)
		}
		if at < 0 {
			return ErrNA
		}
		return Number(float64(at + 1))
	case "CHOISIR", "CHOOSE":
		i, ok := optionalNumber(args, 0)
		if !ok {
			return ErrValue
		}
		if len(args) < 2 {
			return ErrValue
		}
		options := values(args[1:])
		idx := int(i) - 1
		if idx < 0 || idx >= len(options) {
			return ErrValue
		}
		return options[idx]
	case "LIGNES", "ROWS":
		if grid, ok := argGrid(args, 0); ok {
			return Number(float64(len(grid)))
		}
		return Number(1)
	case "COLONNES", "COLUMNS":
		if grid, ok := argGrid(args, 0); ok && len(grid) > 0 {
			return Number(float64(len(grid[0])))
		}
		return Number(1)
	}
	return ErrName
}

// findRow : en mode approché la colonne est supposée triée, et l'on retient la
// dernière entrée inférieure ou égale, comme le fait un tableur.
func findRow(column []Value, needle Value, approximate bool) int {
	if !approximate {
		for i, v := range column {
			if matches(v, needle) {
				return i
			}
		}
		return -1
	}
	target, hasTarget := needle.StrictNumber()
	best := -1
	for i, candidate := range column {
		var c int
		if n, ok := candidate.StrictNumber(); ok && hasTarget {
			switch {
			case n < target:
				c = -1
			case n > target:
				c = 1
			}
		} else {
			c = strings.Compare(strings.ToLower(candidate.AsText()), strings.ToLower(needle.AsText()))
		}
		if c <= 0 {
			best = i
		}
	}
	return best
}

// ---------------------------------------------------------- date et heure

var dateFns = map[string]fn{}

func init() {
	register(dateFns, fnDate,
		"AUJOURDHUI", "TODAY", "MAINTENANT", "NOW", "DATE", "ANNEE", "YEAR",
		"MOIS", "MONTH", "JOUR", "DAY", "JOURSEM", "WEEKDAY", "JOURS", "DAYS",
		"HEURE", "HOUR", "MINUTE", "MOIS.DECALER", "EDATE", "FIN.MOIS", "EOMONTH")
}

// Le jour 0 est le 30 décembre 1899, convention des tableurs.
var serialEpoch = time.Date(1899, time.December, 30, 0, 0, 0, 0, time.UTC)

func dateSerial(y, m, d int) float64 {
	t := time.Date(y, time.Month(m), d, 0, 0, 0, 0, time.UTC)
	return t.Sub(serialEpoch).Hours() / 24
}

func fromSerial(serial float64) time.Time {
	return serialEpoch.Add(time.Duration(math.Floor(serial)) * 24 * time.Hour)
}

func todaySerial() float64 {
	now := time.Now()
	return dateSerial(now.Year(), int(now.Month()), now.Day())
}

func fnDate(name string, args []Arg) Value {
	field := func(f func(time.Time) int) Value {
		s, ok := argValue(args, 0).AsNumber()
		if !ok {
			return ErrValue
		}
		return Number(float64(f(fromSerial(s))))
	}
	switch name {
	case "AUJOURDHUI", "TODAY":
		return Number(todaySerial())
	case "MAINTENANT", "NOW":
		now := time.Now()
		fraction := float64(now.Hour()*3600+now.Minute()*60+now.Second()) / 86400
		return Number(todaySerial() + fraction)
	case "DATE":
		y, ok1 := argValue(args, 0).AsNumber()
		m, ok2 := argValue(args, 1).AsNumber()
		d, ok3 := argValue(args, 2).AsNumber()
		if !ok1 || !ok2 || !ok3 {
			return ErrValue
		}
		return Number(dateSerial(int(y), int(m), int(d)))
	case "ANNEE", "YEAR":
		return field(func(t time.Time) int { return t.Year() })
	case "MOIS", "MONTH":
		return field(func(t time.Time) int { return int(t.Month()) })
	case "JOUR", "DAY":
		return field(func(t time.Time) int { return t.Day() })
	case "JOURSEM", "WEEKDAY":
		return field(func(t time.Time) int { return int(t.Weekday()) + 1 })
	case "HEURE", "HOUR":
		return num(args, 0, func(v float64) Value { return Number(math.Floor((v - math.Floor(v)) * 24)) })
	case "MINUTE":
		return num(args, 0, func(v float64) Value {
			return Number(math.Mod(math.Floor((v-math.Floor(v))*1440), 60))
		})
	case "JOURS", "DAYS":
		end, ok1 := argValue(args, 0).AsNumber()
		start, ok2 := argValue(args, 1).AsNumber()
		if !ok1 || !ok2 {
			return ErrValue
		}
		return Number(math.Floor(end) - math.Floor(start))
	case "MOIS.DECALER", "EDATE", "FIN.MOIS", "EOMONTH":
		s, ok := argValue(args, 0).AsNumber()
		if !ok {
			return ErrValue
		}
		months, _ := optionalNumber(args, 1)
		t := fromSerial(s).AddDate(0, int(months), 0)
		if name == "FIN.MOIS" || name == "EOMONTH" {
			t = time.Date(t.Year(), t.Month(), 1, 0, 0, 0, 0, time.UTC).AddDate(0, 1, -1)
		}
		return Number(dateSerial(t.Year(), int(t.Month()), t.Day()))
	}
	return ErrName
}

// formatWith : TEXTE() rend quelques motifs courants, réellement appliqués.
func formatWith(v Value, pattern string) string {
	lower := strings.ToLower(pattern)
	n, ok := v.AsNumber()
	if !ok {
		return v.AsText()
	}
	if strings.Contains(lower, "aaaa") || strings.Contains(lower, "yyyy") ||
		strings.Contains(lower, "jj") || strings.Contains(lower, "dd") ||
		strings.Contains(lower, "mm") {
		t := fromSerial(n)
		day := pad2(t.Day())
		month := pad2(int(t.Month()))
		year := FormatNumber(float64(t.Year()))
		out := lower
		out = strings.ReplaceAll(out, "aaaa", year)
		out = strings.ReplaceAll(out, "yyyy", year)
		out = strings.ReplaceAll(out, "jj", day)
		out = strings.ReplaceAll(out, "dd", day)
		out = strings.ReplaceAll(out, "mm", month)
		return out
	}
	decimals := strings.Count(afterDot(pattern), "0")
	if strings.HasSuffix(lower, "%") {
		return trimZeroPad(n*100, decimals) + " %"
	}
	text := trimZeroPad(n, decimals)
	if strings.Contains(pattern, ",") || strings.Contains(pattern, " ") {
		return groupThousands(text)
	}
	return text
}

func afterDot(s string) string {
	if i := strings.Index(s, "."); i >= 0 {
		return s[i+1:]
	}
	return ""
}

func pad2(n int) string {
	if n < 10 {
		return "0" + FormatNumber(float64(n))
	}
	return FormatNumber(float64(n))
}

func trimZeroPad(v float64, decimals int) string {
	if decimals <= 0 {
		return FormatNumber(math.Round(v))
	}
	f := math.Pow(10, float64(decimals))
	rounded := math.Round(v*f) / f
	s := FormatNumber(rounded)
	if !strings.Contains(s, ".") {
		s += "." + strings.Repeat("0", decimals)
		return s
	}
	missing := decimals - len(afterDot(s))
	if missing > 0 {
		s += strings.Repeat("0", missing)
	}
	return s
}

func groupThousands(text string) string {
	negative := strings.HasPrefix(text, "-")
	body := strings.TrimPrefix(text, "-")
	integer := body
	rest := ""
	if i := strings.IndexAny(body, ".,"); i >= 0 {
		integer, rest = body[:i], body[i:]
	}
	var chunks []string
	for len(integer) > 3 {
		chunks = append([]string{integer[len(integer)-3:]}, chunks...)
		integer = integer[:len(integer)-3]
	}
	chunks = append([]string{integer}, chunks...)
	out := strings.Join(chunks, " ") + rest
	if negative {
		return "-" + out
	}
	return out
}

// ---------------------------------------------------------------- finance

var financeFns = map[string]fn{}

func init() {
	register(financeFns, fnFinance, "VPM", "PMT", "VC", "FV", "VA", "PV", "NPM", "NPER")
}

func fnFinance(name string, args []Arg) Value {
	rate, ok := argValue(args, 0).AsNumber()
	if !ok {
		return ErrValue
	}
	opt := func(i int) float64 { v, _ := optionalNumber(args, i); return v }

	switch name {
	case "VPM", "PMT":
		n, ok := argValue(args, 1).AsNumber()
		pv, ok2 := argValue(args, 2).AsNumber()
		if !ok || !ok2 {
			return ErrValue
		}
		if n == 0 {
			return ErrDiv0
		}
		fv, typ := opt(3), opt(4)
		if rate == 0 {
			return Number(-(pv + fv) / n)
		}
		growth := math.Pow(1+rate, n)
		return Number(-(pv*growth + fv) * rate / ((1 + rate*typ) * (growth - 1)))
	case "VC", "FV":
		n, ok := argValue(args, 1).AsNumber()
		if !ok {
			return ErrValue
		}
		pmt, pv, typ := opt(2), opt(3), opt(4)
		if rate == 0 {
			return Number(-(pv + pmt*n))
		}
		growth := math.Pow(1+rate, n)
		return Number(-(pv*growth + pmt*(1+rate*typ)*(growth-1)/rate))
	case "VA", "PV":
		n, ok := argValue(args, 1).AsNumber()
		if !ok {
			return ErrValue
		}
		pmt, fv, typ := opt(2), opt(3), opt(4)
		if rate == 0 {
			return Number(-(fv + pmt*n))
		}
		growth := math.Pow(1+rate, n)
		return Number(-(fv + pmt*(1+rate*typ)*(growth-1)/rate) / growth)
	case "NPM", "NPER":
		pmt, ok := argValue(args, 1).AsNumber()
		pv, ok2 := argValue(args, 2).AsNumber()
		if !ok || !ok2 {
			return ErrValue
		}
		fv, typ := opt(3), opt(4)
		if rate == 0 {
			if pmt == 0 {
				return ErrDiv0
			}
			return Number(-(pv + fv) / pmt)
		}
		adjusted := pmt * (1 + rate*typ)
		numerator := adjusted - fv*rate
		denominator := adjusted + pv*rate
		if numerator <= 0 || denominator <= 0 {
			return ErrNum
		}
		return Number(math.Log(numerator/denominator) / math.Log(1+rate))
	}
	return ErrName
}
