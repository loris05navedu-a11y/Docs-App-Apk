// Package server expose le classeur à l'interface, par HTTP sur la boucle
// locale. Rien n'écoute vers l'extérieur : l'adresse est 127.0.0.1 et le port
// est choisi par le système au démarrage.
package server

import (
	"encoding/json"
	"fmt"
	"io/fs"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"sync"

	"github.com/docssuite/tableur/internal/dialog"
	"github.com/docssuite/tableur/internal/engine"
	"github.com/docssuite/tableur/internal/fileio"
	"github.com/docssuite/tableur/internal/sheet"
)

type Server struct {
	mu       sync.Mutex
	book     *sheet.Book
	path     string
	modified bool
	assets   fs.FS
}

func New(assets fs.FS) *Server {
	return &Server{book: sheet.New(), assets: assets}
}

// Listen ouvre le port sur la boucle locale et rend l'adresse à afficher.
func (s *Server) Listen() (string, net.Listener, error) {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return "", nil, err
	}
	return fmt.Sprintf("http://%s", ln.Addr().String()), ln, nil
}

func (s *Server) Handler() http.Handler {
	mux := http.NewServeMux()
	mux.Handle("/", http.FileServer(http.FS(s.assets)))
	mux.HandleFunc("/api/state", s.handleState)
	mux.HandleFunc("/api/cell", s.handleCell)
	mux.HandleFunc("/api/format", s.handleFormat)
	mux.HandleFunc("/api/op", s.handleOp)
	mux.HandleFunc("/api/functions", s.handleFunctions)
	mux.HandleFunc("/api/file", s.handleFile)
	mux.HandleFunc("/api/evaluate", s.handleEvaluate)
	return mux
}

// ------------------------------------------------------------------ état

type stateResponse struct {
	Name      string                  `json:"name"`
	Path      string                  `json:"path"`
	Modified  bool                    `json:"modified"`
	Rows      int                     `json:"rows"`
	Columns   int                     `json:"columns"`
	Cells     map[string]string       `json:"cells"`
	Display   map[string]string       `json:"display"`
	Formats   map[string]sheet.Format `json:"formats"`
	DialogsOK bool                    `json:"dialogsOk"`
}

// state compose la réponse. Les valeurs affichées sont recalculées à chaque
// envoi : c'est le seul moyen de tenir à jour les formules qui dépendent
// d'une cellule que l'on vient de modifier ailleurs.
func (s *Server) state() stateResponse {
	cells := s.book.EngineCells()
	display := make(map[string]string, len(s.book.Cells))
	for ref := range s.book.Cells {
		display[ref] = engine.Display(ref, cells)
	}
	name := s.book.Name
	if s.path != "" {
		name = strings.TrimSuffix(filepath.Base(s.path), filepath.Ext(s.path))
	}
	return stateResponse{
		Name:      name,
		Path:      s.path,
		Modified:  s.modified,
		Rows:      s.book.Rows,
		Columns:   s.book.Columns,
		Cells:     s.book.Cells,
		Display:   display,
		Formats:   s.book.Formats,
		DialogsOK: dialog.Available,
	}
}

func (s *Server) writeState(w http.ResponseWriter) {
	writeJSON(w, s.state())
}

func writeJSON(w http.ResponseWriter, payload any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	_ = json.NewEncoder(w).Encode(payload)
}

func fail(w http.ResponseWriter, message string) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(http.StatusBadRequest)
	_ = json.NewEncoder(w).Encode(map[string]string{"error": message})
}

func decode(r *http.Request, target any) error {
	defer r.Body.Close()
	return json.NewDecoder(r.Body).Decode(target)
}

func (s *Server) handleState(w http.ResponseWriter, r *http.Request) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.writeState(w)
}

func (s *Server) handleFunctions(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, engine.Catalog)
}

// handleEvaluate sert la barre de calcul rapide, sans toucher au classeur.
func (s *Server) handleEvaluate(w http.ResponseWriter, r *http.Request) {
	var body struct {
		Expression string `json:"expression"`
	}
	if err := decode(r, &body); err != nil {
		fail(w, "requête illisible")
		return
	}
	s.mu.Lock()
	cells := s.book.EngineCells()
	s.mu.Unlock()
	v, ok := engine.Evaluate(body.Expression, cells)
	if !ok {
		writeJSON(w, map[string]string{"result": "", "error": "Formule invalide"})
		return
	}
	writeJSON(w, map[string]string{"result": v.AsText()})
}

func (s *Server) handleCell(w http.ResponseWriter, r *http.Request) {
	var body struct {
		Ref string `json:"ref"`
		Raw string `json:"raw"`
	}
	if err := decode(r, &body); err != nil {
		fail(w, "requête illisible")
		return
	}
	ref := strings.ToUpper(strings.TrimSpace(body.Ref))
	if !engine.IsCellRef(ref) {
		fail(w, "référence de cellule invalide")
		return
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	s.book.Set(ref, strings.TrimSpace(body.Raw))
	s.modified = true
	s.writeState(w)
}

func (s *Server) handleFormat(w http.ResponseWriter, r *http.Request) {
	var body struct {
		Refs       []string `json:"refs"`
		Bold       *bool    `json:"bold"`
		Italic     *bool    `json:"italic"`
		Color      *string  `json:"color"`
		Background *string  `json:"background"`
		Align      *string  `json:"align"`
		Clear      bool     `json:"clear"`
	}
	if err := decode(r, &body); err != nil {
		fail(w, "requête illisible")
		return
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	for _, raw := range body.Refs {
		ref := strings.ToUpper(strings.TrimSpace(raw))
		if !engine.IsCellRef(ref) {
			continue
		}
		if body.Clear {
			s.book.SetFormat(ref, sheet.Format{})
			continue
		}
		f := s.book.Formats[ref]
		if body.Bold != nil {
			f.Bold = *body.Bold
		}
		if body.Italic != nil {
			f.Italic = *body.Italic
		}
		if body.Color != nil {
			f.Color = *body.Color
		}
		if body.Background != nil {
			f.Background = *body.Background
		}
		if body.Align != nil {
			f.Align = *body.Align
		}
		s.book.SetFormat(ref, f)
	}
	s.modified = true
	s.writeState(w)
}

func (s *Server) handleOp(w http.ResponseWriter, r *http.Request) {
	var body struct {
		Op        string `json:"op"`
		Index     int    `json:"index"`
		Column    int    `json:"column"`
		FirstRow  int    `json:"firstRow"`
		Ascending bool   `json:"ascending"`
	}
	if err := decode(r, &body); err != nil {
		fail(w, "requête illisible")
		return
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	switch body.Op {
	case "insertRow":
		s.book.InsertRow(body.Index)
	case "deleteRow":
		s.book.DeleteRow(body.Index)
	case "insertColumn":
		s.book.InsertColumn(body.Index)
	case "deleteColumn":
		s.book.DeleteColumn(body.Index)
	case "addRows":
		s.book.Rows += 20
	case "addColumns":
		s.book.Columns += 4
	case "sort":
		lastRow, _ := s.book.Extent()
		if lastRow < body.FirstRow {
			lastRow = body.FirstRow
		}
		s.book.Sort(body.Column, body.FirstRow, lastRow, body.Ascending)
	case "clear":
		name := s.book.Name
		s.book = sheet.New()
		s.book.Name = name
	default:
		fail(w, "opération inconnue : "+body.Op)
		return
	}
	s.modified = true
	s.writeState(w)
}

// ------------------------------------------------------------- fichiers

var (
	nativeFilter = [][2]string{{"Classeur Tableur (*.tab)", "*.tab"}, {"Tous les fichiers", "*.*"}}
	openFilter   = [][2]string{
		{"Tous les tableurs", "*.tab;*.xlsx;*.csv"},
		{"Classeur Tableur (*.tab)", "*.tab"},
		{"Classeur Excel (*.xlsx)", "*.xlsx"},
		{"Texte séparé (*.csv)", "*.csv"},
	}
	xlsxFilter = [][2]string{{"Classeur Excel (*.xlsx)", "*.xlsx"}}
	csvFilter  = [][2]string{{"Texte séparé (*.csv)", "*.csv"}}
)

func (s *Server) handleFile(w http.ResponseWriter, r *http.Request) {
	var body struct {
		Action string `json:"action"`
		Path   string `json:"path"`
	}
	if err := decode(r, &body); err != nil {
		fail(w, "requête illisible")
		return
	}
	s.mu.Lock()
	defer s.mu.Unlock()

	switch body.Action {
	case "new":
		s.book = sheet.New()
		s.path = ""
		s.modified = false
		s.writeState(w)

	case "open":
		path := body.Path
		if path == "" {
			path = dialog.Open("Ouvrir un classeur", openFilter)
		}
		if path == "" {
			s.writeState(w)
			return
		}
		if err := s.load(path); err != nil {
			fail(w, err.Error())
			return
		}
		s.writeState(w)

	case "save":
		if s.path == "" {
			s.saveAs(w, "tab", nativeFilter, body.Path)
			return
		}
		if err := s.write(s.path); err != nil {
			fail(w, err.Error())
			return
		}
		s.modified = false
		s.writeState(w)

	case "saveAs":
		s.saveAs(w, "tab", nativeFilter, body.Path)

	case "exportXlsx":
		s.saveAs(w, "xlsx", xlsxFilter, body.Path)

	case "exportCsv":
		s.saveAs(w, "csv", csvFilter, body.Path)

	default:
		fail(w, "action inconnue : "+body.Action)
	}
}

func (s *Server) saveAs(w http.ResponseWriter, ext string, filter [][2]string, given string) {
	path := given
	if path == "" {
		path = dialog.Save("Enregistrer", ext, s.book.Name+"."+ext, filter)
	}
	if path == "" {
		s.writeState(w)
		return
	}
	if filepath.Ext(path) == "" {
		path += "." + ext
	}
	if err := s.write(path); err != nil {
		fail(w, err.Error())
		return
	}
	// Un export ne devient pas le fichier courant : le classeur reste
	// rattaché à son propre format.
	if strings.EqualFold(filepath.Ext(path), ".tab") {
		s.path = path
		s.modified = false
	}
	s.writeState(w)
}

func (s *Server) load(path string) error {
	data, err := os.ReadFile(path)
	if err != nil {
		return fmt.Errorf("ce fichier n'a pas pu être lu")
	}
	var book *sheet.Book
	switch strings.ToLower(filepath.Ext(path)) {
	case ".xlsx":
		book, err = fileio.ImportXLSX(data)
	case ".csv", ".txt":
		book, err = fileio.ImportCSV(data, detectSeparator(data))
	default:
		book, err = fileio.LoadJSON(data)
	}
	if err != nil {
		return err
	}
	book.Name = strings.TrimSuffix(filepath.Base(path), filepath.Ext(path))
	s.book = book
	s.path = path
	s.modified = false
	return nil
}

func (s *Server) write(path string) error {
	var data []byte
	var err error
	switch strings.ToLower(filepath.Ext(path)) {
	case ".xlsx":
		data, err = fileio.ExportXLSX(s.book)
	case ".csv":
		data = fileio.ExportCSV(s.book, ';')
	default:
		data, err = fileio.SaveJSON(s.book)
	}
	if err != nil {
		return err
	}
	if err := os.WriteFile(path, data, 0o644); err != nil {
		return fmt.Errorf("l'enregistrement a échoué : le dossier est peut-être protégé")
	}
	return nil
}

// detectSeparator devine entre le point-virgule et la virgule d'après la
// première ligne : les CSV produits en français utilisent le point-virgule.
func detectSeparator(data []byte) rune {
	line := string(data)
	if i := strings.IndexAny(line, "\r\n"); i > 0 {
		line = line[:i]
	}
	if strings.Count(line, ";") >= strings.Count(line, ",") {
		return ';'
	}
	return ','
}

// Book expose le classeur aux tests.
func (s *Server) Book() *sheet.Book {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.book
}
