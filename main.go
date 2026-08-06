package main

import (
	"context"
	"embed"
	"errors"
	"io/fs"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"syscall"
	"time"
)

const version = "0.4.0"

//go:embed web/dist
var webAssets embed.FS

type config struct {
	addr         string
	libraryDir   string
	dataDir      string
	fontsDir     string
	password     string
	scanInterval time.Duration
}

func main() {
	if err := run(); err != nil {
		slog.Error("server stopped", "error", err)
		os.Exit(1)
	}
}

func run() error {
	cfg, err := loadConfig()
	if err != nil {
		return err
	}

	web, err := fs.Sub(webAssets, "web/dist")
	if err != nil {
		return err
	}
	store, err := openStore(filepath.Join(cfg.dataDir, "lumosreader.db"))
	if err != nil {
		return err
	}
	defer store.Close()

	app := newApp(cfg, store, web)
	if _, err := app.scan(); err != nil {
		return err
	}

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	go app.scanLoop(ctx)

	server := &http.Server{
		Addr:              cfg.addr,
		Handler:           app.routes(),
		ReadHeaderTimeout: 10 * time.Second,
		IdleTimeout:       60 * time.Second,
	}

	go func() {
		<-ctx.Done()
		shutdownCtx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		defer cancel()
		_ = server.Shutdown(shutdownCtx)
	}()

	slog.Info("微光阅已启动", "address", cfg.addr, "library", cfg.libraryDir, "auth", cfg.password != "")
	err = server.ListenAndServe()
	if errors.Is(err, http.ErrServerClosed) {
		return nil
	}
	return err
}

func loadConfig() (config, error) {
	scanInterval, err := time.ParseDuration(env("SCAN_INTERVAL", "15m"))
	if err != nil {
		return config{}, errors.New("SCAN_INTERVAL must be a duration such as 15m")
	}
	dataDir := env("DATA_DIR", "./data")
	cfg := config{
		addr:         env("ADDR", "127.0.0.1:8080"),
		libraryDir:   env("LIBRARY_DIR", "./library"),
		dataDir:      dataDir,
		fontsDir:     env("FONTS_DIR", filepath.Join(dataDir, "fonts")),
		password:     os.Getenv("ADMIN_PASSWORD"),
		scanInterval: scanInterval,
	}
	for _, directory := range []string{cfg.dataDir, cfg.fontsDir} {
		if err := os.MkdirAll(directory, 0o750); err != nil {
			return config{}, err
		}
	}
	return cfg, nil
}

func env(key, fallback string) string {
	if value := os.Getenv(key); value != "" {
		return value
	}
	return fallback
}
