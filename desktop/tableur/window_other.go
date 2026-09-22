//go:build !windows

package main

import "net"

// Hors Windows, l'application sert au développement : l'interface s'ouvre
// dans le navigateur.
func openWindow(url string) { openBrowser(url) }

func listenOn(addr string) (net.Listener, error) { return net.Listen("tcp", addr) }
