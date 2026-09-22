#!/bin/sh
# Produit l'exécutable Windows. Fonctionne depuis Linux, macOS ou Windows :
# Go compile pour une autre plateforme sans outillage supplémentaire.
set -e
cd "$(dirname "$0")"

echo "Tests…"
go test ./...

echo "Compilation pour Windows x64…"
mkdir -p dist
GOOS=windows GOARCH=amd64 CGO_ENABLED=0 \
  go build -trimpath -ldflags="-H windowsgui -s -w" -o dist/Tableur.exe .

echo "Terminé : dist/Tableur.exe ($(du -h dist/Tableur.exe | cut -f1))"
