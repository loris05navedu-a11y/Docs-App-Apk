package engine

import (
	"math"
	"strconv"
	"strings"
)

// Cells donne accès au contenu brut des cellules, indexé par référence A1.
type Cells map[string]string

type cycleError struct{}
type parseError struct{ msg string }

// Arg est un argument de fonction : soit une valeur, soit une plage qui garde
// sa forme rectangulaire — ce dont RECHERCHEV et INDEX ont besoin.
type Arg struct {
	Value   Value
	Grid    [][]Value
	IsRange bool
}

func one(v Value) Arg { return Arg{Value: v} }

// values aplatit les arguments, plages comprises, en lecture ligne par ligne.
func values(args []Arg) []Value {
	out := make([]Value, 0, len(args))
	for _, a := range args {
		if a.IsRange {
			for _, row := range a.Grid {
				out = append(out, row...)
			}
		} else {
			out = append(out, a.Value)
		}
	}
	return out
}

// numbers ne retient que les vrais nombres, comme le fait SOMME sur une plage.
func numbers(args []Arg) []float64 {
	var out []float64
	for _, v := range values(args) {
		if n, ok := v.StrictNumber(); ok {
			out = append(out, n)
		}
	}
	return out
}

func argValue(args []Arg, i int) Value {
	if i < 0 || i >= len(args) {
		return Blank
	}
	a := args[i]
	if !a.IsRange {
		return a.Value
	}
	for _, row := range a.Grid {
		if len(row) > 0 {
			return row[0]
		}
	}
	return Blank
}

// optionalNumber distingue « argument omis » de « argument valant zéro ».
// Sans cela INDEX(plage;3) désignerait la colonne 0.
func optionalNumber(args []Arg, i int) (float64, bool) {
	if i < 0 || i >= len(args) {
		return 0, false
	}
	v := argValue(args, i)
	if v.Kind == KindBlank {
		return 0, false
	}
	n, ok := v.AsNumber()
	return n, ok
}

func argGrid(args []Arg, i int) ([][]Value, bool) {
	if i < 0 || i >= len(args) || !args[i].IsRange {
		return nil, false
	}
	return args[i].Grid, true
}

func flatGrid(args []Arg, i int) ([]Value, bool) {
	g, ok := argGrid(args, i)
	if !ok {
		return nil, false
	}
	var out []Value
	for _, row := range g {
		out = append(out, row...)
	}
	return out, true
}

type parser struct {
	src      string
	pos      int
	cells    Cells
	visiting map[string]bool
}

// Evaluate évalue une expression libre. La seconde valeur est fausse si la
// formule est mal écrite — ce qui n'est pas la même chose qu'une erreur de
// calcul, laquelle est portée par la valeur rendue.
func Evaluate(expression string, cells Cells) (result Value, ok bool) {
	cleaned := strings.TrimSpace(strings.TrimPrefix(strings.TrimSpace(expression), "="))
	if cleaned == "" {
		return Blank, false
	}
	defer func() {
		if r := recover(); r != nil {
			if _, isCycle := r.(cycleError); isCycle {
				result, ok = ErrCycle, true
				return
			}
			if _, isParse := r.(parseError); isParse {
				result, ok = ErrParse, false
				return
			}
			panic(r)
		}
	}()
	p := &parser{src: cleaned, cells: cells, visiting: map[string]bool{}}
	return p.parseAll(), true
}

// Display rend le texte affiché d'une cellule, formule évaluée s'il y a lieu.
func Display(ref string, cells Cells) string {
	raw, present := cells[ref]
	if !present {
		return ""
	}
	if !strings.HasPrefix(raw, "=") {
		return raw
	}
	v := evalFormula(raw[1:], cells, map[string]bool{ref: true})
	return v.AsText()
}

func evalFormula(body string, cells Cells, visiting map[string]bool) (result Value) {
	defer func() {
		if r := recover(); r != nil {
			if _, isCycle := r.(cycleError); isCycle {
				result = ErrCycle
				return
			}
			if _, isParse := r.(parseError); isParse {
				result = ErrParse
				return
			}
			panic(r)
		}
	}()
	p := &parser{src: body, cells: cells, visiting: visiting}
	return p.parseAll()
}

// ValueAt rend la valeur typée d'une cellule, formule comprise.
func ValueAt(ref string, cells Cells, visiting map[string]bool) Value {
	if visiting[ref] {
		panic(cycleError{})
	}
	visiting[ref] = true
	defer delete(visiting, ref)

	raw := strings.TrimSpace(cells[ref])
	if raw == "" {
		return Blank
	}
	if strings.HasPrefix(raw, "=") {
		return evalFormula(raw[1:], cells, visiting)
	}
	return Literal(raw)
}

func (p *parser) parseAll() Value {
	v := p.parseComparison()
	p.skipWs()
	if p.pos < len(p.src) {
		panic(parseError{"caractère inattendu"})
	}
	return v
}

func (p *parser) skipWs() {
	for p.pos < len(p.src) && (p.src[p.pos] == ' ' || p.src[p.pos] == '\t') {
		p.pos++
	}
}

func (p *parser) parseComparison() Value {
	left := p.parseConcat()
	p.skipWs()
	for _, op := range []string{"<>", ">=", "<=", ">", "<", "="} {
		if strings.HasPrefix(p.src[p.pos:], op) {
			p.pos += len(op)
			right := p.parseConcat()
			if left.IsErr() {
				return left
			}
			if right.IsErr() {
				return right
			}
			return Bool(compareValues(left, right, op))
		}
	}
	return left
}

// compareValues : deux nombres se comparent numériquement ; dès qu'un texte
// entre en jeu, la comparaison porte sur le libellé, casse ignorée.
func compareValues(left, right Value, op string) bool {
	a, aok := left.StrictNumber()
	b, bok := right.StrictNumber()
	var c int
	if aok && bok {
		switch {
		case a < b:
			c = -1
		case a > b:
			c = 1
		}
	} else {
		c = strings.Compare(strings.ToLower(left.AsText()), strings.ToLower(right.AsText()))
	}
	switch op {
	case "<>":
		return c != 0
	case ">=":
		return c >= 0
	case "<=":
		return c <= 0
	case ">":
		return c > 0
	case "<":
		return c < 0
	}
	return c == 0
}

// parseConcat traite `&`, qui colle deux valeurs bout à bout.
func (p *parser) parseConcat() Value {
	v := p.parseExpr()
	for {
		p.skipWs()
		if p.pos < len(p.src) && p.src[p.pos] == '&' {
			p.pos++
			r := p.parseExpr()
			if v.IsErr() {
				return v
			}
			if r.IsErr() {
				return r
			}
			v = Text(v.AsText() + r.AsText())
			continue
		}
		return v
	}
}

func (p *parser) parseExpr() Value {
	v := p.parseTerm()
	for {
		p.skipWs()
		if p.pos < len(p.src) && (p.src[p.pos] == '+' || p.src[p.pos] == '-') {
			op := p.src[p.pos]
			p.pos++
			r := p.parseTerm()
			v = arithmetic(v, r, func(a, b float64) float64 {
				if op == '+' {
					return a + b
				}
				return a - b
			})
			continue
		}
		return v
	}
}

func (p *parser) parseTerm() Value {
	v := p.parsePower()
	for {
		p.skipWs()
		if p.pos < len(p.src) && (p.src[p.pos] == '*' || p.src[p.pos] == '/') {
			op := p.src[p.pos]
			p.pos++
			r := p.parsePower()
			if op == '/' {
				if n, ok := r.AsNumber(); ok && n == 0 {
					return ErrDiv0
				}
			}
			v = arithmetic(v, r, func(a, b float64) float64 {
				if op == '*' {
					return a * b
				}
				return a / b
			})
			continue
		}
		return v
	}
}

func (p *parser) parsePower() Value {
	base := p.parseUnary()
	p.skipWs()
	if p.pos < len(p.src) && p.src[p.pos] == '^' {
		p.pos++
		return arithmetic(base, p.parsePower(), math.Pow)
	}
	return base
}

func (p *parser) parseUnary() Value {
	p.skipWs()
	if p.pos < len(p.src) && p.src[p.pos] == '-' {
		p.pos++
		return arithmetic(Number(0), p.parseUnary(), func(a, b float64) float64 { return a - b })
	}
	if p.pos < len(p.src) && p.src[p.pos] == '+' {
		p.pos++
		return p.parseUnary()
	}
	v := p.parsePrimary()
	p.skipWs()
	if p.pos < len(p.src) && p.src[p.pos] == '%' {
		p.pos++
		return arithmetic(v, Number(100), func(a, b float64) float64 { return a / b })
	}
	return v
}

func arithmetic(a, b Value, op func(float64, float64) float64) Value {
	if a.IsErr() {
		return a
	}
	if b.IsErr() {
		return b
	}
	x, xok := a.AsNumber()
	y, yok := b.AsNumber()
	if !xok || !yok {
		return ErrValue
	}
	return Number(op(x, y))
}

func (p *parser) parsePrimary() Value {
	p.skipWs()
	if p.pos >= len(p.src) {
		panic(parseError{"formule incomplète"})
	}
	c := p.src[p.pos]
	if c == '(' {
		p.pos++
		v := p.parseComparison()
		p.skipWs()
		if p.pos < len(p.src) && p.src[p.pos] == ')' {
			p.pos++
		} else {
			panic(parseError{"')' manquante"})
		}
		return v
	}
	if c == '"' {
		return p.parseString()
	}
	if c == '\'' {
		// 'Ventes 2024'!A1 — nom de feuille entre apostrophes.
		prefix, ok := p.readSheetPrefix()
		if !ok {
			panic(parseError{"nom de feuille invalide"})
		}
		return p.parseNameOrRefWithSheet(prefix)
	}
	if isDigit(c) || c == '.' {
		return p.parseNumber()
	}
	if isLetter(c) || c == '_' {
		return p.parseNameOrRef()
	}
	panic(parseError{"caractère invalide"})
}

func isDigit(c byte) bool  { return c >= '0' && c <= '9' }
func isLetter(c byte) bool { return c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z' }

// parseString lit un texte entre guillemets ; `""` insère un guillemet.
func (p *parser) parseString() Value {
	p.pos++
	var sb strings.Builder
	for p.pos < len(p.src) {
		ch := p.src[p.pos]
		if ch == '"' {
			if p.pos+1 < len(p.src) && p.src[p.pos+1] == '"' {
				sb.WriteByte('"')
				p.pos += 2
				continue
			}
			p.pos++
			return Text(sb.String())
		}
		sb.WriteByte(ch)
		p.pos++
	}
	panic(parseError{"guillemet fermant manquant"})
}

func (p *parser) parseNumber() Value {
	start := p.pos
	for p.pos < len(p.src) && (isDigit(p.src[p.pos]) || p.src[p.pos] == '.') {
		p.pos++
	}
	n, err := strconv.ParseFloat(p.src[start:p.pos], 64)
	if err != nil {
		panic(parseError{"nombre invalide"})
	}
	return Number(n)
}

// readSheetPrefix lit « Nom! » ou « 'Nom avec espaces'! » s'il y en a un.
// La position reste inchangée quand rien ne correspond.
func (p *parser) readSheetPrefix() (string, bool) {
	save := p.pos
	if p.pos < len(p.src) && p.src[p.pos] == '\'' {
		end := strings.IndexByte(p.src[p.pos+1:], '\'')
		if end < 0 {
			p.pos = save
			return "", false
		}
		name := p.src[p.pos+1 : p.pos+1+end]
		after := p.pos + end + 2
		if after < len(p.src) && p.src[after] == '!' {
			p.pos = after + 1
			return name, true
		}
		p.pos = save
		return "", false
	}
	j := p.pos
	for j < len(p.src) && (isLetter(p.src[j]) || isDigit(p.src[j]) || p.src[j] == '_') {
		j++
	}
	if j > p.pos && j < len(p.src) && p.src[j] == '!' {
		name := p.src[p.pos:j]
		p.pos = j + 1
		return name, true
	}
	p.pos = save
	return "", false
}

// Qualify compose la clé d'une cellule d'une autre feuille.
func Qualify(sheetName, ref string) string {
	if sheetName == "" {
		return ref
	}
	return sheetName + "!" + ref
}

func (p *parser) parseNameOrRefWithSheet(sheetName string) Value {
	start := p.pos
	for p.pos < len(p.src) && (isLetter(p.src[p.pos]) || isDigit(p.src[p.pos])) {
		p.pos++
	}
	ref := strings.ToUpper(p.src[start:p.pos])
	if !IsCellRef(ref) {
		panic(parseError{"référence invalide après le nom de feuille"})
	}
	return ValueAt(Qualify(sheetName, ref), p.cells, p.visiting)
}

func (p *parser) parseNameOrRef() Value {
	// Un nom suivi de « ! » désigne une feuille, pas une fonction.
	if prefix, ok := p.readSheetPrefix(); ok {
		return p.parseNameOrRefWithSheet(prefix)
	}
	start := p.pos
	// Le point fait partie des noms français : NB.SI, ARRONDI.SUP…
	for p.pos < len(p.src) {
		c := p.src[p.pos]
		if isLetter(c) || isDigit(c) || c == '_' || c == '.' {
			p.pos++
			continue
		}
		break
	}
	name := p.src[start:p.pos]
	p.skipWs()

	if p.pos < len(p.src) && p.src[p.pos] == '(' {
		p.pos++
		var args []Arg
		p.skipWs()
		if p.pos < len(p.src) && p.src[p.pos] == ')' {
			p.pos++
		} else {
			for {
				args = append(args, p.parseArgument())
				p.skipWs()
				if p.pos < len(p.src) && (p.src[p.pos] == ';' || p.src[p.pos] == ',') {
					p.pos++
					continue
				}
				if p.pos < len(p.src) && p.src[p.pos] == ')' {
					p.pos++
					break
				}
				panic(parseError{"arguments invalides"})
			}
		}
		return apply(strings.ToUpper(name), args)
	}

	// Une référence ne contient pas de point : on le rend à l'expression.
	if strings.Contains(name, ".") {
		head := name[:strings.Index(name, ".")]
		if IsCellRef(head) {
			p.pos = start + len(head)
			name = head
		}
	}
	return p.constantOrRef(name)
}

// parseArgument reconnaît une plage A1:B3, sinon évalue une expression.
func (p *parser) parseArgument() Arg {
	save := p.pos
	p.skipWs()
	// Une plage peut désigner une autre feuille : Feuille2!A1:B3.
	sheetName, _ := p.readSheetPrefix()
	start := p.pos
	if p.pos < len(p.src) && isLetter(p.src[p.pos]) {
		for p.pos < len(p.src) && isLetter(p.src[p.pos]) {
			p.pos++
		}
		colEnd := p.pos
		for p.pos < len(p.src) && isDigit(p.src[p.pos]) {
			p.pos++
		}
		refEnd := p.pos
		if colEnd < refEnd {
			p.skipWs()
			if p.pos < len(p.src) && p.src[p.pos] == ':' {
				p.pos++
				p.skipWs()
				start2 := p.pos
				for p.pos < len(p.src) && isLetter(p.src[p.pos]) {
					p.pos++
				}
				colEnd2 := p.pos
				for p.pos < len(p.src) && isDigit(p.src[p.pos]) {
					p.pos++
				}
				if colEnd2 < p.pos {
					return Arg{
						Grid:    p.grid(sheetName, p.src[start:refEnd], p.src[start2:p.pos]),
						IsRange: true,
					}
				}
			}
		}
	}
	p.pos = save
	return one(p.parseComparison())
}

func (p *parser) grid(sheetName, from, to string) [][]Value {
	r1, c1, ok1 := SplitRef(strings.ToUpper(from))
	r2, c2, ok2 := SplitRef(strings.ToUpper(to))
	if !ok1 || !ok2 {
		panic(parseError{"plage invalide"})
	}
	if r1 > r2 {
		r1, r2 = r2, r1
	}
	if c1 > c2 {
		c1, c2 = c2, c1
	}
	rows := make([][]Value, 0, r2-r1+1)
	for r := r1; r <= r2; r++ {
		row := make([]Value, 0, c2-c1+1)
		for c := c1; c <= c2; c++ {
			row = append(row, ValueAt(Qualify(sheetName, CellKey(r, c)), p.cells, p.visiting))
		}
		rows = append(rows, row)
	}
	return rows
}

func (p *parser) constantOrRef(name string) Value {
	switch strings.ToUpper(name) {
	case "PI":
		return Number(math.Pi)
	case "E":
		return Number(math.E)
	case "VRAI", "TRUE":
		return Bool(true)
	case "FAUX", "FALSE":
		return Bool(false)
	}
	upper := strings.ToUpper(name)
	if !IsCellRef(upper) {
		panic(parseError{"référence invalide : " + name})
	}
	return ValueAt(upper, p.cells, p.visiting)
}
