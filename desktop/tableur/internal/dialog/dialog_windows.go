//go:build windows

// Package dialog ouvre les boîtes de fichiers du système.
package dialog

import (
	"syscall"
	"unsafe"
)

var (
	comdlg32         = syscall.NewLazyDLL("comdlg32.dll")
	procGetOpenFile  = comdlg32.NewProc("GetOpenFileNameW")
	procGetSaveFile  = comdlg32.NewProc("GetSaveFileNameW")
	shell32          = syscall.NewLazyDLL("shell32.dll")
	procShellExecute = shell32.NewProc("ShellExecuteW")
)

// openFileName reprend la structure OPENFILENAMEW de l'API Windows.
type openFileName struct {
	StructSize      uint32
	Owner           uintptr
	Instance        uintptr
	Filter          *uint16
	CustomFilter    *uint16
	MaxCustomFilter uint32
	FilterIndex     uint32
	File            *uint16
	MaxFile         uint32
	FileTitle       *uint16
	MaxFileTitle    uint32
	InitialDir      *uint16
	Title           *uint16
	Flags           uint32
	FileOffset      uint16
	FileExtension   uint16
	DefExt          *uint16
	CustData        uintptr
	FnHook          uintptr
	TemplateName    *uint16
	PvReserved      uintptr
	DwReserved      uint32
	FlagsEx         uint32
}

const (
	ofnFileMustExist    = 0x00001000
	ofnPathMustExist    = 0x00000800
	ofnOverwritePrompt  = 0x00000002
	ofnNoChangeDir      = 0x00000008
	ofnExplorer         = 0x00080000
)

// filter compose la chaîne à double terminaison attendue par Windows :
// chaque paire libellé/motif est séparée par un zéro, la liste par deux.
func filter(pairs [][2]string) *uint16 {
	var buf []uint16
	for _, p := range pairs {
		buf = append(buf, utf16Of(p[0])...)
		buf = append(buf, 0)
		buf = append(buf, utf16Of(p[1])...)
		buf = append(buf, 0)
	}
	buf = append(buf, 0)
	return &buf[0]
}

func utf16Of(s string) []uint16 {
	out, err := syscall.UTF16FromString(s)
	if err != nil {
		return []uint16{0}
	}
	return out[:len(out)-1] // sans le zéro final, ajouté par l'appelant
}

func run(proc *syscall.LazyProc, title, defExt string, pairs [][2]string, suggested string, flags uint32) string {
	buf := make([]uint16, 4096)
	if suggested != "" {
		copy(buf, append(utf16Of(suggested), 0))
	}
	titlePtr, _ := syscall.UTF16PtrFromString(title)
	extPtr, _ := syscall.UTF16PtrFromString(defExt)

	ofn := openFileName{
		Filter:      filter(pairs),
		FilterIndex: 1,
		File:        &buf[0],
		MaxFile:     uint32(len(buf)),
		Title:       titlePtr,
		DefExt:      extPtr,
		Flags:       flags | ofnExplorer | ofnNoChangeDir,
	}
	ofn.StructSize = uint32(unsafe.Sizeof(ofn))

	ret, _, _ := proc.Call(uintptr(unsafe.Pointer(&ofn)))
	if ret == 0 {
		return ""
	}
	return syscall.UTF16ToString(buf)
}

// Open demande un fichier à ouvrir. Rend "" si l'utilisateur annule.
func Open(title string, pairs [][2]string) string {
	return run(procGetOpenFile, title, "", pairs, "", ofnFileMustExist|ofnPathMustExist)
}

// Save demande où enregistrer. Rend "" si l'utilisateur annule.
func Save(title, defExt, suggested string, pairs [][2]string) string {
	return run(procGetSaveFile, title, defExt, pairs, suggested, ofnOverwritePrompt|ofnPathMustExist)
}

// Reveal ouvre un fichier avec l'application associée.
func Reveal(path string) {
	verb, _ := syscall.UTF16PtrFromString("open")
	target, _ := syscall.UTF16PtrFromString(path)
	procShellExecute.Call(0, uintptr(unsafe.Pointer(verb)), uintptr(unsafe.Pointer(target)), 0, 0, 1)
}

// Available indique que les dialogues natifs sont utilisables.
const Available = true
