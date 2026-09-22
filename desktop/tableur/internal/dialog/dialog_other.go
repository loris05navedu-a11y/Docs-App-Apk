//go:build !windows

package dialog

// Hors Windows, l'application sert au développement et aux tests : il n'y a
// pas de boîte de dialogue système, l'interface propose alors une saisie de
// chemin.
func Open(title string, pairs [][2]string) string { return "" }

func Save(title, defExt, suggested string, pairs [][2]string) string { return "" }

func Reveal(path string) {}

const Available = false
