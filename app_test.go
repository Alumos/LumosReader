package main

import (
	"archive/zip"
	"bytes"
	"mime/multipart"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"slices"
	"strings"
	"testing"
	"time"
)

func TestLibraryScanAndNaturalOrder(t *testing.T) {
	root := t.TempDir()
	for _, name := range []string{"page10.cbz", "page2.cbz", "ignored.md"} {
		if err := os.WriteFile(filepath.Join(root, name), nil, 0o600); err != nil {
			t.Fatal(err)
		}
	}
	library := newLibrary(root)
	count, err := library.Scan()
	if err != nil {
		t.Fatal(err)
	}
	if count != 2 {
		t.Fatalf("got %d books, want 2", count)
	}
	if naturalKey("page2.jpg") >= naturalKey("page10.jpg") {
		t.Fatal("natural page order is incorrect")
	}
}

func TestRelativeLibraryRoot(t *testing.T) {
	root := t.TempDir()
	t.Chdir(root)
	directory := filepath.Join("library", "冒險")
	if err := os.MkdirAll(directory, 0o700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(directory, "book.cbz"), nil, 0o600); err != nil {
		t.Fatal(err)
	}
	library := newLibrary("./library")
	if _, err := library.Scan(Bookshelf{Name: "冒險", Path: "冒險"}); err != nil {
		t.Fatal(err)
	}
}

func TestDiscoverDirectoriesIncludesFullTree(t *testing.T) {
	root := t.TempDir()
	deep := filepath.Join(root, "漫画", "爱情", "作品", "卷册")
	if err := os.MkdirAll(deep, 0o700); err != nil {
		t.Fatal(err)
	}
	directories, err := discoverDirectories(root)
	if err != nil {
		t.Fatal(err)
	}
	want := filepath.ToSlash(strings.TrimPrefix(deep, root+string(filepath.Separator)))
	if !slices.Contains(directories, want) {
		t.Fatalf("directory tree omitted %q: %v", want, directories)
	}
}

func TestProgressRoundTrip(t *testing.T) {
	store, err := openStore(filepath.Join(t.TempDir(), "test.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer store.Close()
	want := Progress{BookID: "book", Position: .25, Locator: "page-2", UpdatedAt: time.Now().UTC().Round(0)}
	if err := store.SaveProgress(want); err != nil {
		t.Fatal(err)
	}
	got, found, err := store.Progress(want.BookID)
	if err != nil || !found {
		t.Fatalf("read progress: found=%v err=%v", found, err)
	}
	if got != want {
		t.Fatalf("got %#v, want %#v", got, want)
	}
}

func TestBookshelfClassificationAndReadingStats(t *testing.T) {
	root := t.TempDir()
	directory := filepath.Join(root, "漫画", "爱情", "躲在超市后门抽烟的两人")
	if err := os.MkdirAll(directory, 0o700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(directory, "第01卷.cbz"), nil, 0o600); err != nil {
		t.Fatal(err)
	}
	library := newLibrary(root)
	if _, err := library.Scan(Bookshelf{Name: "漫画馆", Path: "漫画"}); err != nil {
		t.Fatal(err)
	}
	book := library.List()[0]
	if book.FileName != "第01卷.cbz" || book.Shelf != "漫画馆" || book.ShelfKind != "comic" || book.Category != "爱情" || book.Series != "躲在超市后门抽烟的两人" || !book.IsComic || book.PageDirection != "rtl" {
		t.Fatalf("unexpected classification: %#v", book)
	}
	store, err := openStore(filepath.Join(t.TempDir(), "test.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer store.Close()
	now := time.Now()
	if err := store.AddReadingTime(book.ID, 30, now); err != nil {
		t.Fatal(err)
	}
	if err := store.AddReadingTime(book.ID, 15, now); err != nil {
		t.Fatal(err)
	}
	stats, err := store.ReadingStats(now)
	if err != nil || stats.TodaySeconds != 45 || stats.TotalSeconds != 45 || len(stats.Books) != 1 {
		t.Fatalf("unexpected stats: %#v err=%v", stats, err)
	}
}

func TestBookshelfKindRoundTrip(t *testing.T) {
	store, err := openStore(filepath.Join(t.TempDir(), "test.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer store.Close()
	want := []Bookshelf{{Name: "漫画", Path: "漫画", Kind: "comic"}}
	if err := store.ReplaceShelves(want); err != nil {
		t.Fatal(err)
	}
	got, err := store.Shelves()
	if err != nil || len(got) != 1 || got[0] != want[0] {
		t.Fatalf("got %#v, want %#v, err=%v", got, want, err)
	}
}

func TestEPUBMetadata(t *testing.T) {
	filename := filepath.Join(t.TempDir(), "book.epub")
	file, err := os.Create(filename)
	if err != nil {
		t.Fatal(err)
	}
	archive := zip.NewWriter(file)
	if err := archive.AddFS(os.DirFS("testdata/demo-epub")); err != nil {
		t.Fatal(err)
	}
	if err := archive.Close(); err != nil {
		t.Fatal(err)
	}
	if err := file.Close(); err != nil {
		t.Fatal(err)
	}
	metadata, err := readEPUBMetadata(filename)
	if err != nil {
		t.Fatal(err)
	}
	if metadata.Title != "微光阅读样章" || metadata.Author != "Alumos" || metadata.Cover != "OEBPS/cover.svg" {
		t.Fatalf("unexpected metadata: %#v", metadata)
	}
}

func TestComicEPUBMetadata(t *testing.T) {
	root := t.TempDir()
	directory := filepath.Join(root, "冒險", "系列")
	if err := os.MkdirAll(directory, 0o700); err != nil {
		t.Fatal(err)
	}
	filename := filepath.Join(directory, "comic.epub")
	file, err := os.Create(filename)
	if err != nil {
		t.Fatal(err)
	}
	archive := zip.NewWriter(file)
	entries := map[string]string{
		"META-INF/container.xml": `<container><rootfiles><rootfile full-path="book.opf"/></rootfiles></container>`,
		"book.opf":               `<package xmlns:dc="http://purl.org/dc/elements/1.1/"><metadata><dc:title>漫画</dc:title><dc:series>系列</dc:series><meta property="rendition:layout">pre-paginated</meta><meta name="book-type" content="comic"/></metadata><manifest></manifest><spine page-progression-direction="rtl"></spine></package>`,
	}
	for name, content := range entries {
		writer, err := archive.Create(name)
		if err != nil {
			t.Fatal(err)
		}
		if _, err := writer.Write([]byte(content)); err != nil {
			t.Fatal(err)
		}
	}
	if err := archive.Close(); err != nil {
		t.Fatal(err)
	}
	if err := file.Close(); err != nil {
		t.Fatal(err)
	}
	metadata, err := readEPUBMetadata(filename)
	if err != nil || !metadata.Comic || metadata.Direction != "rtl" || metadata.Series != "系列" {
		t.Fatalf("unexpected comic metadata: %#v err=%v", metadata, err)
	}
	library := newLibrary(root)
	for range 2 {
		if _, err := library.Scan(Bookshelf{Name: "冒險", Path: "冒險"}); err != nil {
			t.Fatal(err)
		}
	}
	book := library.List()[0]
	if book.Category != "" || book.Series != "系列" || !book.IsComic || book.PageDirection != "rtl" {
		t.Fatalf("series folder leaked into navigation: %#v", book)
	}
}

func TestComicEPUBPages(t *testing.T) {
	filename := filepath.Join(t.TempDir(), "comic.epub")
	file, err := os.Create(filename)
	if err != nil {
		t.Fatal(err)
	}
	archive := zip.NewWriter(file)
	entries := map[string]string{
		"META-INF/container.xml": `<container><rootfiles><rootfile full-path="OEBPS/book.opf" media-type="application/oebps-package+xml"/></rootfiles></container>`,
		"OEBPS/book.opf":         `<package><manifest><item id="p2" href="pages/2.xhtml" media-type="application/xhtml+xml"/><item id="p1" href="pages/1.xhtml" media-type="application/xhtml+xml"/><item id="i1" href="images/1.jpg" media-type="image/jpeg"/><item id="i2" href="images/2.jpg" media-type="image/jpeg"/></manifest><spine><itemref idref="p1"/><itemref idref="p2"/></spine></package>`,
		"OEBPS/pages/1.xhtml":    `<html><body><img src="../images/1.jpg"/></body></html>`,
		"OEBPS/pages/2.xhtml":    `<html xmlns:xlink="http://www.w3.org/1999/xlink"><body><svg><image xlink:href="../images/2.jpg"/></svg></body></html>`,
		"OEBPS/images/1.jpg":     "one",
		"OEBPS/images/2.jpg":     "two",
	}
	for name, content := range entries {
		writer, createErr := archive.Create(name)
		if createErr != nil {
			t.Fatal(createErr)
		}
		if _, writeErr := writer.Write([]byte(content)); writeErr != nil {
			t.Fatal(writeErr)
		}
	}
	if err := archive.Close(); err != nil {
		t.Fatal(err)
	}
	if err := file.Close(); err != nil {
		t.Fatal(err)
	}
	reader, err := zip.OpenReader(filename)
	if err != nil {
		t.Fatal(err)
	}
	defer reader.Close()
	pages, err := epubComicFiles(reader.File)
	if err != nil || len(pages) != 2 || pages[0].Name != "OEBPS/images/1.jpg" || pages[1].Name != "OEBPS/images/2.jpg" {
		t.Fatalf("unexpected pages: %v err=%v", pages, err)
	}
}

func TestContentRangeAndSecurityHeaders(t *testing.T) {
	root := t.TempDir()
	if err := os.WriteFile(filepath.Join(root, "book.epub"), []byte("0123456789"), 0o600); err != nil {
		t.Fatal(err)
	}
	library := newLibrary(root)
	if _, err := library.Scan(); err != nil {
		t.Fatal(err)
	}
	request := httptest.NewRequest(http.MethodGet, "/api/books/"+library.List()[0].ID+"/content", nil)
	request.Header.Set("Range", "bytes=2-5")
	response := httptest.NewRecorder()
	(&app{library: library}).routes().ServeHTTP(response, request)
	if response.Code != http.StatusPartialContent || response.Body.String() != "2345" {
		t.Fatalf("got status %d body %q", response.Code, response.Body.String())
	}
	if csp := response.Header().Get("Content-Security-Policy"); response.Header().Get("Content-Range") != "bytes 2-5/10" || !strings.Contains(csp, "connect-src 'self' blob:") || !strings.Contains(csp, "frame-ancestors 'self'") {
		t.Fatalf("missing range or security headers: %v", response.Header())
	}
	if response.Header().Get("Cache-Control") != "private, no-store" {
		t.Fatalf("book content must not be cached: %v", response.Header())
	}
}

func TestFontUploadAndDownload(t *testing.T) {
	fonts := t.TempDir()
	var body bytes.Buffer
	form := multipart.NewWriter(&body)
	file, err := form.CreateFormFile("font", "reader.ttf")
	if err != nil {
		t.Fatal(err)
	}
	fontData := "\x00\x01\x00\x00font-data"
	if _, err := file.Write([]byte(fontData)); err != nil {
		t.Fatal(err)
	}
	if err := form.Close(); err != nil {
		t.Fatal(err)
	}
	a := &app{cfg: config{fontsDir: fonts}}
	upload := httptest.NewRequest(http.MethodPost, "/api/fonts", &body)
	upload.Header.Set("Content-Type", form.FormDataContentType())
	response := httptest.NewRecorder()
	a.routes().ServeHTTP(response, upload)
	if response.Code != http.StatusCreated {
		t.Fatalf("upload status %d: %s", response.Code, response.Body.String())
	}
	download := httptest.NewRequest(http.MethodGet, "/api/fonts/reader.ttf", nil)
	response = httptest.NewRecorder()
	a.routes().ServeHTTP(response, download)
	if response.Code != http.StatusOK || response.Body.String() != fontData {
		t.Fatalf("download status %d: %q", response.Code, response.Body.String())
	}
	if err := os.WriteFile(filepath.Join(fonts, "mounted.otf"), []byte("OTTOfont-data"), 0o640); err != nil {
		t.Fatal(err)
	}
	list := httptest.NewRequest(http.MethodGet, "/api/fonts", nil)
	response = httptest.NewRecorder()
	a.routes().ServeHTTP(response, list)
	if response.Code != http.StatusOK || !strings.Contains(response.Body.String(), "mounted.otf") {
		t.Fatalf("mounted font was not discovered: %d %s", response.Code, response.Body.String())
	}
}
