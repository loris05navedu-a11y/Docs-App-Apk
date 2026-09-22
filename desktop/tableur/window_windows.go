//go:build windows

package main

import (
	"log"
	"net"
	"syscall"

	"github.com/jchv/go-webview2"
)

// Sans cette déclaration, Windows met l'application à l'échelle en étirant son
// image : sur un écran haute densité tout paraîtrait flou.
func declareDpiAwareness() {
	user32 := syscall.NewLazyDLL("user32.dll")
	if proc := user32.NewProc("SetProcessDpiAwarenessContext"); proc.Find() == nil {
		// -4 : PER_MONITOR_AWARE_V2, disponible depuis Windows 10 1703.
		if ret, _, _ := proc.Call(^uintptr(3)); ret != 0 {
			return
		}
	}
	// Repli pour les versions plus anciennes.
	if proc := syscall.NewLazyDLL("shcore.dll").NewProc("SetProcessDpiAwareness"); proc.Find() == nil {
		proc.Call(2) // PROCESS_PER_MONITOR_DPI_AWARE
		return
	}
	if proc := user32.NewProc("SetProcessDPIAware"); proc.Find() == nil {
		proc.Call()
	}
}

// openWindow ouvre la fenêtre native. WebView2 accompagne Edge : il est
// présent d'origine sur Windows 11 et sur Windows 10 à jour. En son absence
// la création échoue, et l'interface bascule alors sur le navigateur.
func openWindow(url string) {
	declareDpiAwareness()

	defer func() {
		if r := recover(); r != nil {
			log.Println("fenêtre native indisponible :", r)
			openBrowser(url)
		}
	}()

	w := webview2.NewWithOptions(webview2.WebViewOptions{
		Debug: false,
		WindowOptions: webview2.WindowOptions{
			Title:  "Tableur",
			Width:  1280,
			Height: 800,
			Center: true,
		},
	})
	if w == nil {
		openBrowser(url)
		return
	}
	defer w.Destroy()
	w.SetSize(1280, 800, webview2.HintNone)
	w.Navigate(url)
	w.Run()
}

func listenOn(addr string) (net.Listener, error) { return net.Listen("tcp", addr) }
