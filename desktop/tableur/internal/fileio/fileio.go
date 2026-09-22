// Package fileio lit et écrit les formats de fichier du tableur.
package fileio

import (
	"archive/zip"
	"bytes"
	"encoding/csv"
	"encoding/json"
	"encoding/xml"
	"fmt"
	"io"
	"sort"
	"strconv"
	"strings"

	"github.com/docssuite/tableur/internal/engine"
	"github.com/docssuite/tableur/internal/sheet"
)

// ---------------------------------------------------------- format natif

func SaveJSON(w *sheet.Workbook) ([]byte, error) { return json.MarshalIndent(w, "", "  ") }

func LoadJSON(data []byte) (*sheet.Workbook, error) {
	book := sheet.New()
	if err := json.Unmarshal(data, book); err != nil {
		return nil, fmt.Errorf("ce fichier n'est pas un classeur lisible")
	}
	if len(book.Sheets) == 0 {
		book.Sheets = []*sheet.Sheet{sheet.NewSheet("Feuille1")}
	}
	for _, s := range book.Sheets {
		if s.Cells == nil {
			s.Cells = map[string]string{}
		}
		if s.Formats == nil {
			s.Formats = map[string]sheet.Format{}
		}
		if s.Rows < 1 {
			s.Rows = 100
		}
		if s.Columns < 1 {
			s.Columns = 26
		}
		if s.Name == "" {
			s.Name = "Feuille1"
		}
	}
	return book, nil
}

// ------------------------------------------------------------------- CSV

// ExportCSV écrit les valeurs affichées de la feuille active. Un CSV ne sait
// pas porter de formules, et c'est le résultat qu'on attend d'un tel fichier.
func ExportCSV(book *sheet.Workbook, separator rune) []byte {
	cells := book.EngineCells()
	current := book.Current()
	lastRow, lastCol := current.Extent()
	var buf bytes.Buffer
	w := csv.NewWriter(&buf)
	w.Comma = separator
	for r := 0; r <= lastRow; r++ {
		record := make([]string, lastCol+1)
		for c := 0; c <= lastCol; c++ {
			record[c] = engine.Display(engine.CellKey(r, c), cells)
		}
		_ = w.Write(record)
	}
	w.Flush()
	return buf.Bytes()
}

func ImportCSV(data []byte, separator rune) (*sheet.Workbook, error) {
	r := csv.NewReader(bytes.NewReader(data))
	r.Comma = separator
	r.FieldsPerRecord = -1
	r.LazyQuotes = true
	records, err := r.ReadAll()
	if err != nil {
		return nil, fmt.Errorf("ce CSV n'a pas pu être lu : %w", err)
	}
	book := sheet.New()
	s := book.Current()
	for row, record := range records {
		for col, value := range record {
			if strings.TrimSpace(value) != "" {
				s.Cells[engine.CellKey(row, col)] = value
			}
		}
		if col := len(record); col > s.Columns {
			s.Columns = col
		}
	}
	if len(records) > s.Rows {
		s.Rows = len(records)
	}
	return book, nil
}

// ------------------------------------------------------------------ XLSX

const (
	nsMain = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
	nsRel  = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
	nsPkg  = "http://schemas.openxmlformats.org/package/2006/relationships"
	nsCT   = "http://schemas.openxmlformats.org/package/2006/content-types"
)

// ExportXLSX produit un classeur lisible par Excel et LibreOffice, avec
// toutes ses feuilles.
func ExportXLSX(book *sheet.Workbook) ([]byte, error) {
	var buf bytes.Buffer
	z := zip.NewWriter(&buf)
	add := func(name, content string) error {
		w, err := z.Create(name)
		if err != nil {
			return err
		}
		_, err = io.WriteString(w, content)
		return err
	}

	sheets := book.Sheets
	styles, ordered := styleIndexes(book)

	var overrides, entries, rels strings.Builder
	for i, s := range sheets {
		n := i + 1
		overrides.WriteString(fmt.Sprintf(
			`<Override PartName="/xl/worksheets/sheet%d.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>`, n))
		entries.WriteString(fmt.Sprintf(
			`<sheet name="%s" sheetId="%d" r:id="rId%d"/>`, xmlEscape(sheetName(s.Name, i)), n, n))
		rels.WriteString(fmt.Sprintf(
			`<Relationship Id="rId%d" Type="%s/worksheet" Target="worksheets/sheet%d.xml"/>`, n, nsRel, n))
	}
	rels.WriteString(fmt.Sprintf(
		`<Relationship Id="rId%d" Type="%s/styles" Target="styles.xml"/>`, len(sheets)+1, nsRel))

	parts := [][2]string{
		{"[Content_Types].xml", `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>` +
			`<Types xmlns="` + nsCT + `">` +
			`<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>` +
			`<Default Extension="xml" ContentType="application/xml"/>` +
			`<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>` +
			overrides.String() +
			`<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>` +
			`</Types>`},
		{"_rels/.rels", `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>` +
			`<Relationships xmlns="` + nsPkg + `">` +
			`<Relationship Id="rId1" Type="` + nsRel + `/officeDocument" Target="xl/workbook.xml"/>` +
			`</Relationships>`},
		{"xl/workbook.xml", `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>` +
			`<workbook xmlns="` + nsMain + `" xmlns:r="` + nsRel + `">` +
			`<sheets>` + entries.String() + `</sheets></workbook>`},
		{"xl/_rels/workbook.xml.rels", `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>` +
			`<Relationships xmlns="` + nsPkg + `">` + rels.String() + `</Relationships>`},
		{"xl/styles.xml", stylesXML(ordered)},
	}
	for _, p := range parts {
		if err := add(p[0], p[1]); err != nil {
			return nil, err
		}
	}
	cells := book.EngineCells()
	for i, s := range sheets {
		// Chaque feuille est évaluée dans son propre contexte, sinon les
		// références courtes viseraient toujours la feuille active.
		local := localView(book, s, cells)
		if err := add(fmt.Sprintf("xl/worksheets/sheet%d.xml", i+1), sheetXML(s, local, styles)); err != nil {
			return nil, err
		}
	}
	if err := z.Close(); err != nil {
		return nil, err
	}
	return buf.Bytes(), nil
}

// localView place les cellules de la feuille donnée sous leur nom court, en
// gardant les autres feuilles accessibles par leur nom qualifié.
func localView(book *sheet.Workbook, s *sheet.Sheet, all engine.Cells) engine.Cells {
	out := make(engine.Cells, len(all))
	for k, v := range all {
		if !strings.Contains(k, "!") {
			continue
		}
		out[k] = v
	}
	for ref, raw := range s.Cells {
		out[ref] = raw
	}
	return out
}

func sheetName(name string, index int) string {
	clean := strings.Map(func(r rune) rune {
		if strings.ContainsRune(`[]:*?/\`, r) {
			return -1
		}
		return r
	}, name)
	if clean == "" {
		clean = fmt.Sprintf("Feuille%d", index+1)
	}
	if len([]rune(clean)) > 31 {
		clean = string([]rune(clean)[:31])
	}
	return clean
}

// styleIndexes attribue un numéro de style à chaque mise en forme distincte
// du classeur entier : le tableau des styles est partagé entre les feuilles.
func styleIndexes(book *sheet.Workbook) (map[sheet.Format]int, []sheet.Format) {
	index := map[sheet.Format]int{}
	var ordered []sheet.Format
	var seen []sheet.Format
	for _, s := range book.Sheets {
		for _, f := range s.Formats {
			if f.IsZero() {
				continue
			}
			if _, has := index[f]; !has {
				index[f] = -1
				seen = append(seen, f)
			}
		}
	}
	// L'ordre d'un parcours de map varie : on le fixe pour que deux
	// enregistrements du même classeur donnent le même fichier.
	sort.Slice(seen, func(i, j int) bool { return fmt.Sprint(seen[i]) < fmt.Sprint(seen[j]) })
	for _, f := range seen {
		index[f] = len(ordered) + 1 // 0 est le style par défaut
		ordered = append(ordered, f)
	}
	return index, ordered
}

func stylesXML(ordered []sheet.Format) string {
	var fonts, fills, xfs strings.Builder

	fonts.WriteString(`<font><sz val="11"/><name val="Calibri"/></font>`)
	fills.WriteString(`<fill><patternFill patternType="none"/></fill>`)
	fills.WriteString(`<fill><patternFill patternType="gray125"/></fill>`)
	xfs.WriteString(`<xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>`)

	for i, f := range ordered {
		fonts.WriteString(`<font><sz val="11"/><name val="Calibri"/>`)
		if f.Bold {
			fonts.WriteString(`<b/>`)
		}
		if f.Italic {
			fonts.WriteString(`<i/>`)
		}
		if rgb := hexRGB(f.Color); rgb != "" {
			fonts.WriteString(`<color rgb="FF` + rgb + `"/>`)
		}
		fonts.WriteString(`</font>`)

		fillID := 0
		if rgb := hexRGB(f.Background); rgb != "" {
			fills.WriteString(`<fill><patternFill patternType="solid"><fgColor rgb="FF` + rgb + `"/><bgColor indexed="64"/></patternFill></fill>`)
			fillID = 2 + i
		}
		xfs.WriteString(fmt.Sprintf(
			`<xf numFmtId="0" fontId="%d" fillId="%d" borderId="0" xfId="0" applyFont="1" applyFill="1"`,
			i+1, fillID))
		if f.Align != "" {
			xfs.WriteString(` applyAlignment="1"><alignment horizontal="` + f.Align + `"/></xf>`)
		} else {
			xfs.WriteString(`/>`)
		}
	}

	return `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>` +
		`<styleSheet xmlns="` + nsMain + `">` +
		fmt.Sprintf(`<fonts count="%d">%s</fonts>`, len(ordered)+1, fonts.String()) +
		fmt.Sprintf(`<fills count="%d">%s</fills>`, len(ordered)+2, fills.String()) +
		`<borders count="1"><border/></borders>` +
		`<cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>` +
		fmt.Sprintf(`<cellXfs count="%d">%s</cellXfs>`, len(ordered)+1, xfs.String()) +
		`</styleSheet>`
}

func sheetXML(s *sheet.Sheet, cells engine.Cells, styles map[sheet.Format]int) string {
	lastRow, lastCol := s.Extent()

	var sb strings.Builder
	sb.WriteString(`<?xml version="1.0" encoding="UTF-8" standalone="yes"?>`)
	sb.WriteString(`<worksheet xmlns="` + nsMain + `"><sheetData>`)
	for r := 0; r <= lastRow; r++ {
		var row strings.Builder
		empty := true
		for c := 0; c <= lastCol; c++ {
			ref := engine.CellKey(r, c)
			raw, has := s.Cells[ref]
			format, hasFormat := s.Formats[ref]
			if !has && (!hasFormat || format.IsZero()) {
				continue
			}
			empty = false
			style := ""
			if idx, ok := styles[format]; ok && idx > 0 {
				style = fmt.Sprintf(` s="%d"`, idx)
			}
			if !has {
				row.WriteString(`<c r="` + ref + `"` + style + `/>`)
				continue
			}
			if strings.HasPrefix(raw, "=") {
				shown := engine.Display(ref, cells)
				row.WriteString(`<c r="` + ref + `"` + style + typeAttr(shown) + `>`)
				row.WriteString(`<f>` + xmlEscape(strings.TrimPrefix(engine.ToEnglish(raw), "=")) + `</f>`)
				row.WriteString(`<v>` + xmlEscape(shown) + `</v>`)
				row.WriteString(`</c>`)
				continue
			}
			value := engine.Literal(raw)
			if value.Kind == engine.KindNum {
				row.WriteString(`<c r="` + ref + `"` + style + `><v>` +
					strconv.FormatFloat(value.Num, 'g', -1, 64) + `</v></c>`)
			} else {
				row.WriteString(`<c r="` + ref + `"` + style + ` t="inlineStr"><is><t xml:space="preserve">` +
					xmlEscape(raw) + `</t></is></c>`)
			}
		}
		if !empty {
			sb.WriteString(fmt.Sprintf(`<row r="%d">%s</row>`, r+1, row.String()))
		}
	}
	sb.WriteString(`</sheetData></worksheet>`)
	return sb.String()
}

// typeAttr : une formule qui rend du texte doit être marquée comme telle,
// sinon Excel essaie de lire le résultat comme un nombre.
func typeAttr(shown string) string {
	if _, err := strconv.ParseFloat(shown, 64); err == nil {
		return ""
	}
	return ` t="str"`
}

func hexRGB(color string) string {
	c := strings.TrimPrefix(strings.TrimSpace(color), "#")
	if len(c) == 8 {
		c = c[2:]
	}
	if len(c) != 6 {
		return ""
	}
	return strings.ToUpper(c)
}

func xmlEscape(s string) string {
	var buf bytes.Buffer
	_ = xml.EscapeText(&buf, []byte(s))
	return buf.String()
}

// ---------------------------------------------------------- lecture XLSX

type xlsxCell struct {
	Ref     string `xml:"r,attr"`
	Type    string `xml:"t,attr"`
	Formula string `xml:"f"`
	Value   string `xml:"v"`
	Inline  struct {
		Text string `xml:"t"`
	} `xml:"is"`
}

type xlsxRow struct {
	Cells []xlsxCell `xml:"c"`
}

type xlsxSheet struct {
	Rows []xlsxRow `xml:"sheetData>row"`
}

type sharedStrings struct {
	Items []struct {
		Text string   `xml:"t"`
		Runs []string `xml:"r>t"`
	} `xml:"si"`
}

type workbookIndex struct {
	Sheets []struct {
		Name string `xml:"name,attr"`
		RID  string `xml:"id,attr"`
	} `xml:"sheets>sheet"`
}

type relationships struct {
	Items []struct {
		ID     string `xml:"Id,attr"`
		Target string `xml:"Target,attr"`
	} `xml:"Relationship"`
}

// ImportXLSX lit toutes les feuilles d'un classeur, dans l'ordre des onglets.
func ImportXLSX(data []byte) (*sheet.Workbook, error) {
	z, err := zip.NewReader(bytes.NewReader(data), int64(len(data)))
	if err != nil {
		return nil, fmt.Errorf("ce fichier n'est pas un classeur Excel lisible")
	}
	read := func(name string) []byte {
		for _, f := range z.File {
			if f.Name == name {
				rc, err := f.Open()
				if err != nil {
					return nil
				}
				defer rc.Close()
				out, _ := io.ReadAll(rc)
				return out
			}
		}
		return nil
	}

	var shared []string
	if raw := read("xl/sharedStrings.xml"); raw != nil {
		var ss sharedStrings
		if xml.Unmarshal(raw, &ss) == nil {
			for _, item := range ss.Items {
				if item.Text != "" {
					shared = append(shared, item.Text)
				} else {
					shared = append(shared, strings.Join(item.Runs, ""))
				}
			}
		}
	}

	// L'ordre des onglets est celui de workbook.xml, pas celui du zip.
	targets := map[string]string{}
	if raw := read("xl/_rels/workbook.xml.rels"); raw != nil {
		var rels relationships
		if xml.Unmarshal(raw, &rels) == nil {
			for _, r := range rels.Items {
				targets[r.ID] = strings.TrimPrefix(r.Target, "/xl/")
			}
		}
	}
	type entry struct{ name, path string }
	var order []entry
	if raw := read("xl/workbook.xml"); raw != nil {
		var index workbookIndex
		if xml.Unmarshal(raw, &index) == nil {
			for _, s := range index.Sheets {
				if target, ok := targets[s.RID]; ok && strings.HasPrefix(target, "worksheets/") {
					order = append(order, entry{s.Name, "xl/" + target})
				}
			}
		}
	}
	if len(order) == 0 {
		for _, f := range z.File {
			if strings.HasPrefix(f.Name, "xl/worksheets/") && strings.HasSuffix(f.Name, ".xml") {
				order = append(order, entry{"", f.Name})
			}
		}
		sort.Slice(order, func(i, j int) bool { return order[i].path < order[j].path })
	}
	if len(order) == 0 {
		return nil, fmt.Errorf("ce classeur ne contient aucune feuille")
	}

	book := &sheet.Workbook{Name: "Classeur", Sheets: nil}
	for i, e := range order {
		raw := read(e.path)
		if raw == nil {
			continue
		}
		var parsed xlsxSheet
		if xml.Unmarshal(raw, &parsed) != nil {
			continue
		}
		name := e.name
		if name == "" {
			name = fmt.Sprintf("Feuille%d", i+1)
		}
		s := sheet.NewSheet(name)
		maxRow, maxCol := 0, 0
		for _, row := range parsed.Rows {
			for _, c := range row.Cells {
				ref := strings.ToUpper(c.Ref)
				r, col, ok := engine.SplitRef(ref)
				if !ok {
					continue
				}
				if r > maxRow {
					maxRow = r
				}
				if col > maxCol {
					maxCol = col
				}
				if c.Formula != "" {
					s.Cells[ref] = engine.ToFrench("=" + c.Formula)
					continue
				}
				switch c.Type {
				case "s":
					if idx, err := strconv.Atoi(c.Value); err == nil && idx >= 0 && idx < len(shared) {
						s.Cells[ref] = shared[idx]
					}
				case "inlineStr":
					if c.Inline.Text != "" {
						s.Cells[ref] = c.Inline.Text
					}
				case "b":
					if c.Value == "1" {
						s.Cells[ref] = "VRAI"
					} else {
						s.Cells[ref] = "FAUX"
					}
				default:
					if c.Value != "" {
						s.Cells[ref] = c.Value
					}
				}
			}
		}
		if maxRow+1 > s.Rows {
			s.Rows = maxRow + 1
		}
		if maxCol+1 > s.Columns {
			s.Columns = maxCol + 1
		}
		book.Sheets = append(book.Sheets, s)
	}
	if len(book.Sheets) == 0 {
		return nil, fmt.Errorf("aucune feuille lisible dans ce classeur")
	}
	return book, nil
}
