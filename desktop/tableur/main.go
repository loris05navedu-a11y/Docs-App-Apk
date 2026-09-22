// Tableur — application de feuille de calcul pour Windows.
//
// L'exécutable porte tout : le moteur de calcul, l'interface et un petit
// serveur qui les relie sur la boucle locale. La fenêtre est une vue WebView2,
// présente d'origine sur Windows 11 et sur Windows 10 à jour ; si elle
// manque, l'interface s'ouvre dans le navigateur par défaut plutôt que de
// refuser de démarrer.
package main

import (
	"embed"
	"flag"
	"fmt"
	"io/fs"
	"log"
	"net/http"
	"os"
	"os/exec"
	"runtime"
	"time"

	"github.com/docssuite/tableur/internal/server"
)

//go:embed web
var embedded embed.FS

func main() {
	headless := flag.Bool("headless", false, "ne pas ouvrir de fenêtre ; sert au développement et aux tests")
	addr := flag.String("addr", "", "adresse d'écoute imposée, par exemple 127.0.0.1:8080")
	flag.Parse()

	assets, err := fs.Sub(embedded, "web")
	if err != nil {
		fatal("ressources introuvables", err)
	}

	app := server.New(assets)
	url, listener, err := app.Listen()
	if err != nil {
		fatal("le port local n'a pas pu être ouvert", err)
	}
	if *addr != "" {
		_ = listener.Close()
		listener, err = listenOn(*addr)
		if err != nil {
			fatal("adresse indisponible", err)
		}
		url = "http://" + *addr
	}

	go func() {
		if err := http.Serve(listener, app.Handler()); err != nil {
			log.Println("serveur arrêté :", err)
		}
	}()
	waitReady(url)

	if *headless {
		fmt.Println(url)
		select {} // le processus reste en vie pour les tests
	}
	openWindow(url)
}

// waitReady attend que le serveur réponde, pour que la fenêtre ne s'ouvre
// jamais sur une page blanche.
func waitReady(url string) {
	client := &http.Client{Timeout: 300 * time.Millisecond}
	for i := 0; i < 100; i++ {
		if resp, err := client.Get(url + "/api/state"); err == nil {
			resp.Body.Close()
			return
		}
		time.Sleep(20 * time.Millisecond)
	}
}

func fatal(message string, err error) {
	log.Printf("%s : %v", message, err)
	os.Exit(1)
}

// openBrowser sert de secours quand la fenêtre native n'est pas disponible.
func openBrowser(url string) {
	switch runtime.GOOS {
	case "windows":
		_ = exec.Command("rundll32", "url.dll,FileProtocolHandler", url).Start()
	case "darwin":
		_ = exec.Command("open", url).Start()
	default:
		_ = exec.Command("xdg-open", url).Start()
	}
	fmt.Println("Tableur est ouvert dans votre navigateur :", url)
	select {}
}
