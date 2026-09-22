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
	"strconv"
	"strings"

	"github.com/docssuite/tableur/internal/engine"
	"github.com/docssuite/tableur/internal/sheet"
)

// ---------------------------------------------------------- format natif

func SaveJSON(b *sheet.Book) ([]byte, error) { return json.MarshalIndent(b, "", "  ") }

func LoadJSON(data []byte) (*sheet.Book, error) {
	b := sheet.New()
	if err := json.Unmarshal(data, b); err != nil {
		return nil, err
	}
	if b.Cells == nil {
		b.Cells = map[string]string{}
	}
	if b.Formats == nil {
		b.Formats = map[string]sheet.Format{}
	}
	if b.Rows < 1 {
		b.Rows = 100
	}
	if b.Columns < 1 {
		b.Columns = 26
	}
	return b, nil
}

// ------------------------------------------------------------------- CSV

// ExportCSV écrit les valeurs affichées, pas les formules : un CSV ne sait
// pas les porter, et c'est le résultat qu'on attend d'un tel fichier.
func ExportCSV(b *sheet.Book, separator rune) []byte {
	cells := b.EngineCells()
	lastRow, lastCol := b.Extent()
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

func ImportCSV(data []byte, separator rune) (*sheet.Book, error) {
	r := csv.NewReader(bytes.NewReader(data))
	r.Comma = separator
	r.FieldsPerRecord = -1
	r.LazyQuotes = true
	records, err := r.ReadAll()
	if err != nil {
		return nil, fmt.Errorf("ce CSV n'a pas pu être lu : %w", err)
	}
	b := sheet.New()
	for row, record := range records {
		for col, value := range record {
			if strings.TrimSpace(value) != "" {
				b.Cells[engine.CellKey(row, col)] = value
			}
		}
		if col := len(record); col > b.Columns {
			b.Columns = col
		}
	}
	if len(records) > b.Rows {
		b.Rows = len(records)
	}
	return b, nil
}

// ------------------------------------------------------------------ XLSX

const (
	nsMain = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
	nsRel  = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
	nsPkg  = "http://schemas.openxmlformats.org/package/2006/relationships"
	nsCT   = "http://schemas.openxmlformats.org/package/2006/content-types"
)

// ExportXLSX produit un classeur lisible par Excel et LibreOffice.
func ExportXLSX(b *sheet.Book) ([]byte, error) {
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

	parts := [][2]string{
		{"[Content_Types].xml", `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>` +
			`<Types xmlns="` + nsCT + `">` +
			`<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>` +
			`<Default Extension="xml" ContentType="application/xml"/>` +
			`<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>` +
			`<Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>` +
			`<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>` +
			`</Types>`},
		{"_rels/.rels", `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>` +
			`<Relationships xmlns="` + nsPkg + `">` +
			`<Relationship Id="rId1" Type="` + nsRel + `/officeDocument" Target="xl/workbook.xml"/>` +
			`</Relationships>`},
		{"xl/workbook.xml", `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>` +
			`<workbook xmlns="` + nsMain + `" xmlns:r="` + nsRel + `">` +
			`<sheets><sheet name="` + xmlEscape(sheetName(b.Name)) + `" sheetId="1" r:id="rId1"/></sheets>` +
			`</workbook>`},
		{"xl/_rels/workbook.xml.rels", `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>` +
			`<Relationships xmlns="` + nsPkg + `">` +
			`<Relationship Id="rId1" Type="` + nsRel + `/worksheet" Target="worksheets/sheet1.xml"/>` +
			`<Relationship Id="rId2" Type="` + nsRel + `/styles" Target="styles.xml"/>` +
			`</Relationships>`},
		{"xl/styles.xml", stylesXML(b)},
		{"xl/worksheets/sheet1.xml", sheetXML(b)},
	}
	for _, p := range parts {
		if err := add(p[0], p[1]); err != nil {
			return nil, err
		}
	}
	if err := z.Close(); err != nil {
		return nil, err
	}
	return buf.Bytes(), nil
}

func sheetName(name string) string {
	clean := strings.Map(func(r rune) rune {
		if strings.ContainsRune(`[]:*?/\`, r) {
			return -1
		}
		return r
	}, name)
	if clean == "" {
		clean = "Feuille1"
	}
	if len([]rune(clean)) > 31 {
		clean = string([]rune(clean)[:31])
	}
	return clean
}

// styleIndexes attribue un numéro de style à chaque mise en forme distincte.
func styleIndexes(b *sheet.Book) (map[sheet.Format]int, []sheet.Format) {
	index := map[sheet.Format]int{}
	var ordered []sheet.Format
	for _, f := range b.Formats {
		if f.IsZero() {
			continue
		}
		if _, seen := index[f]; !seen {
			index[f] = len(ordered) + 1 // 0 est le style par défaut
			ordered = append(ordered, f)
		}
	}
	return index, ordered
}

func stylesXML(b *sheet.Book) string {
	_, ordered := styleIndexes(b)
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

func sheetXML(b *sheet.Book) string {
	cells := b.EngineCells()
	styles, _ := styleIndexes(b)
	lastRow, lastCol := b.Extent()

	var sb strings.Builder
	sb.WriteString(`<?xml version="1.0" encoding="UTF-8" standalone="yes"?>`)
	sb.WriteString(`<worksheet xmlns="` + nsMain + `"><sheetData>`)
	for r := 0; r <= lastRow; r++ {
		var row strings.Builder
		empty := true
		for c := 0; c <= lastCol; c++ {
			ref := engine.CellKey(r, c)
			raw, has := b.Cells[ref]
			format, hasFormat := b.Formats[ref]
			if !has && (!hasFormat || format.IsZero()) {
				continue
			}
			empty = false
			style := ""
			if idx, ok := styles[format]; ok {
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
				row.WriteString(valueNode(shown, false))
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

func valueNode(shown string, _ bool) string {
	if _, err := strconv.ParseFloat(shown, 64); err == nil {
		return `<v>` + shown + `</v>`
	}
	return `<v>` + xmlEscape(shown) + `</v>`
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
		Text  string   `xml:"t"`
		Runs  []string `xml:"r>t"`
	} `xml:"si"`
}

// ImportXLSX lit la première feuille d'un classeur.
func ImportXLSX(data []byte) (*sheet.Book, error) {
	z, err := zip.NewReader(bytes.NewReader(data), int64(len(data)))
	if err != nil {
		return nil, fmt.Errorf("ce fichier n'est pas un classeur Excel lisible : %w", err)
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

	// Le premier `worksheet` du paquet fait office de feuille active.
	var sheetData []byte
	for _, f := range z.File {
		if strings.HasPrefix(f.Name, "xl/worksheets/") && strings.HasSuffix(f.Name, ".xml") {
			sheetData = read(f.Name)
			break
		}
	}
	if sheetData == nil {
		return nil, fmt.Errorf("ce classeur ne contient aucune feuille")
	}

	var parsed xlsxSheet
	if err := xml.Unmarshal(sheetData, &parsed); err != nil {
		return nil, fmt.Errorf("la feuille n'a pas pu être lue : %w", err)
	}

	b := sheet.New()
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
				b.Cells[ref] = engine.ToFrench("=" + c.Formula)
				continue
			}
			switch c.Type {
			case "s":
				if i, err := strconv.Atoi(c.Value); err == nil && i >= 0 && i < len(shared) {
					b.Cells[ref] = shared[i]
				}
			case "inlineStr":
				if c.Inline.Text != "" {
					b.Cells[ref] = c.Inline.Text
				}
			case "b":
				if c.Value == "1" {
					b.Cells[ref] = "VRAI"
				} else {
					b.Cells[ref] = "FAUX"
				}
			default:
				if c.Value != "" {
					b.Cells[ref] = c.Value
				}
			}
		}
	}
	if maxRow+1 > b.Rows {
		b.Rows = maxRow + 1
	}
	if maxCol+1 > b.Columns {
		b.Columns = maxCol + 1
	}
	return b, nil
}
