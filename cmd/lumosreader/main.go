package main

import (
	"log/slog"
	"os"

	"github.com/alumos/lumosreader/internal/server"
	"github.com/alumos/lumosreader/web"
)

func main() {
	assets, err := web.Assets()
	if err == nil {
		err = server.Run(assets)
	}
	if err != nil {
		slog.Error("server stopped", "error", err)
		os.Exit(1)
	}
}
