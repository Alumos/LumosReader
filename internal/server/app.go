package server

import (
	"archive/zip"
	"context"
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"io/fs"
	"log/slog"
	"mime"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"regexp"
	"slices"
	"strconv"
	"strings"
	"sync"
	"time"
)

const maxComicPageSize = 64 << 20

var (
	allowedFormats = map[string]string{
		".epub": "epub",
		".mobi": "mobi",
		".azw3": "azw3",
		".pdf":  "pdf",
		".cbz":  "cbz",
		".zip":  "cbz",
		".txt":  "txt",
	}
	digits = regexp.MustCompile(`\d+`)
)

type Book struct {
	ID            string    `json:"id"`
	Title         string    `json:"title"`
	FileName      string    `json:"file_name"`
	Author        string    `json:"author,omitempty"`
	Format        string    `json:"format"`
	Shelf         string    `json:"shelf"`
	ShelfKind     string    `json:"shelf_kind"`
	Category      string    `json:"category,omitempty"`
	Series        string    `json:"series,omitempty"`
	IsComic       bool      `json:"is_comic"` // Kept for API v4 clients; rendering uses Format and FixedLayout.
	FixedLayout   bool      `json:"fixed_layout,omitempty"`
	PageDirection string    `json:"page_direction,omitempty"`
	Size          int64     `json:"size"`
	Modified      time.Time `json:"modified"`
	Progress      float64   `json:"progress"`
	Locator       string    `json:"locator,omitempty"`
	ProgressTime  time.Time `json:"progress_time,omitzero"`
	CoverURL      string    `json:"cover_url,omitempty"`
	path          string
	coverEntry    string
	pageEntries   []string
}

type library struct {
	root  string
	mu    sync.RWMutex
	books map[string]Book
}

func newLibrary(root string) *library {
	if absolute, err := filepath.Abs(root); err == nil {
		root = absolute
	}
	return &library{root: root, books: make(map[string]Book)}
}

func (l *library) Scan(shelves ...Bookshelf) (int, error) {
	l.mu.RLock()
	previous := make(map[string]Book, len(l.books))
	for _, book := range l.books {
		previous[book.path] = book
	}
	l.mu.RUnlock()

	type scanRoot struct {
		path      string
		shelfName string
		shelfKind string
	}
	roots := []scanRoot{{path: l.root, shelfKind: "auto"}}
	if len(shelves) > 0 {
		roots = roots[:0]
		for _, shelf := range shelves {
			root, err := resolveShelfPath(l.root, shelf.Path)
			if err != nil {
				return 0, err
			}
			roots = append(roots, scanRoot{path: root, shelfName: shelf.Name, shelfKind: shelf.Kind})
		}
	}

	books := make(map[string]Book)
	for _, root := range roots {
		err := filepath.WalkDir(root.path, func(path string, entry fs.DirEntry, walkErr error) error {
			if walkErr != nil {
				return walkErr
			}
			if entry.IsDir() || !entry.Type().IsRegular() {
				return nil
			}
			format, ok := allowedFormats[strings.ToLower(filepath.Ext(path))]
			if !ok {
				return nil
			}
			info, err := entry.Info()
			if err != nil {
				return err
			}
			relative, err := filepath.Rel(l.root, path)
			if err != nil {
				return err
			}
			withinShelf, err := filepath.Rel(root.path, path)
			if err != nil {
				return err
			}
			directories := pathParts(filepath.Dir(withinShelf))
			shelfName := root.shelfName
			if shelfName == "" {
				if len(directories) > 0 {
					shelfName, directories = directories[0], directories[1:]
				} else {
					shelfName = "未分类"
				}
			}
			sum := sha256.Sum256([]byte(filepath.ToSlash(relative)))
			id := hex.EncodeToString(sum[:12])
			author := ""
			if parent := filepath.Dir(relative); parent != "." {
				author = filepath.Base(parent)
			}
			book := Book{
				ID:        id,
				Title:     strings.TrimSuffix(filepath.Base(relative), filepath.Ext(relative)),
				FileName:  filepath.Base(relative),
				Author:    author,
				Format:    format,
				Shelf:     shelfName,
				Category:  part(directories, 0),
				Series:    part(directories, 1),
				ShelfKind: inferredShelfKind(format, relative),
				Size:      info.Size(),
				Modified:  info.ModTime(),
				path:      path,
			}
			if format == "cbz" && book.ShelfKind == "comic" {
				book.PageDirection = "rtl"
			}
			if cached, ok := previous[path]; ok && cached.Size == info.Size() && cached.Modified.Equal(info.ModTime()) {
				cached.ID, cached.FileName, cached.path = id, book.FileName, path
				cached.Shelf, cached.Category = book.Shelf, book.Category
				if cached.Series != "" && cached.Category == cached.Series {
					cached.Category = ""
				}
				applyShelfKind(&cached, root.shelfKind)
				books[id] = cached
				return nil
			}
			switch format {
			case "epub":
				if metadata, err := readEPUBMetadata(path); err == nil {
					if metadata.Title != "" {
						book.Title = metadata.Title
					}
					if metadata.Author != "" {
						book.Author = metadata.Author
					}
					if metadata.Series != "" {
						book.Series = metadata.Series
					}
					book.FixedLayout = metadata.FixedLayout
					if metadata.Direction != "" {
						book.PageDirection = metadata.Direction
					}
					book.coverEntry = metadata.Cover
					if metadata.Cover != "" {
						book.CoverURL = "/api/books/" + id + "/cover"
					}
				}
			case "cbz":
				book.CoverURL = "/api/books/" + id + "/cover"
				book.pageEntries = comicEntries(path)
			}
			if book.Series != "" && book.Category == book.Series {
				book.Category = ""
			}
			applyShelfKind(&book, root.shelfKind)
			books[id] = book
			return nil
		})
		if err != nil {
			return 0, fmt.Errorf("scan library: %w", err)
		}
	}
	l.mu.Lock()
	l.books = books
	l.mu.Unlock()
	return len(books), nil
}

func pathParts(directory string) []string {
	if directory == "." || directory == "" {
		return nil
	}
	return strings.Split(filepath.ToSlash(directory), "/")
}

func part(parts []string, index int) string {
	if index < len(parts) {
		return parts[index]
	}
	return ""
}

func inferredShelfKind(format, relative string) string {
	if format == "cbz" {
		return "comic"
	}
	name := strings.ToLower(filepath.ToSlash(relative))
	if strings.Contains(name, "漫画") || strings.Contains(name, "manga") || strings.Contains(name, "comic") || strings.Contains(name, "コミック") {
		return "comic"
	}
	return "book"
}

func applyShelfKind(book *Book, kind string) {
	switch kind {
	case "comic":
		book.ShelfKind = "comic"
		if book.Format == "cbz" && book.PageDirection == "" {
			book.PageDirection = "rtl"
		}
	case "book":
		book.ShelfKind = "book"
	default:
		if book.ShelfKind == "" {
			book.ShelfKind = inferredShelfKind(book.Format, book.path)
		}
	}
	book.IsComic = book.ShelfKind == "comic"
}

func (l *library) List() []Book {
	l.mu.RLock()
	defer l.mu.RUnlock()
	books := make([]Book, 0, len(l.books))
	for _, book := range l.books {
		books = append(books, book)
	}
	slices.SortFunc(books, func(a, b Book) int {
		if order := b.Modified.Compare(a.Modified); order != 0 {
			return order
		}
		return strings.Compare(a.Title, b.Title)
	})
	return books
}

func (l *library) Get(id string) (Book, bool) {
	l.mu.RLock()
	defer l.mu.RUnlock()
	book, ok := l.books[id]
	return book, ok
}

func resolveShelfPath(root, relative string) (string, error) {
	root, err := filepath.Abs(root)
	if err != nil {
		return "", err
	}
	relative = filepath.Clean(filepath.FromSlash(relative))
	if filepath.IsAbs(relative) || relative == ".." || strings.HasPrefix(relative, ".."+string(filepath.Separator)) {
		return "", errors.New("书架目录必须位于挂载书库内")
	}
	resolved := filepath.Join(root, relative)
	info, err := os.Stat(resolved)
	if err != nil || !info.IsDir() {
		return "", errors.New("书架目录不存在")
	}
	return resolved, nil
}

func discoverDirectories(root string) ([]string, error) {
	root, err := filepath.Abs(root)
	if err != nil {
		return nil, err
	}
	directories := []string{"."}
	err = filepath.WalkDir(root, func(path string, entry fs.DirEntry, walkErr error) error {
		if walkErr != nil {
			return walkErr
		}
		if !entry.IsDir() || path == root {
			return nil
		}
		relative, err := filepath.Rel(root, path)
		if err != nil {
			return err
		}
		directories = append(directories, filepath.ToSlash(relative))
		return nil
	})
	return directories, err
}

type app struct {
	cfg      config
	library  *library
	store    *store
	web      fs.FS
	sessions struct {
		sync.Mutex
		items map[string]time.Time
	}
}

func newApp(cfg config, store *store, web fs.FS) *app {
	a := &app{cfg: cfg, library: newLibrary(cfg.libraryDir), store: store, web: web}
	a.sessions.items = make(map[string]time.Time)
	return a
}

func (a *app) routes() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /api/server", a.handleServer)
	mux.HandleFunc("GET /api/session", a.handleSession)
	mux.HandleFunc("POST /api/session", a.handleLogin)
	mux.Handle("DELETE /api/session", a.auth(http.HandlerFunc(a.handleLogout)))
	mux.Handle("GET /api/books", a.auth(http.HandlerFunc(a.handleBooks)))
	mux.Handle("POST /api/scan", a.auth(http.HandlerFunc(a.handleScan)))
	mux.Handle("GET /api/shelves", a.auth(http.HandlerFunc(a.handleShelves)))
	mux.Handle("PUT /api/shelves", a.auth(http.HandlerFunc(a.handleSaveShelves)))
	mux.Handle("GET /api/stats", a.auth(http.HandlerFunc(a.handleStats)))
	mux.Handle("GET /api/fonts", a.auth(http.HandlerFunc(a.handleFonts)))
	mux.Handle("POST /api/fonts", a.auth(http.HandlerFunc(a.handleUploadFont)))
	mux.Handle("GET /api/fonts/{name}", a.auth(http.HandlerFunc(a.handleFont)))
	mux.Handle("GET /api/books/{id}", a.auth(http.HandlerFunc(a.handleBook)))
	mux.Handle("GET /api/books/{id}/content", a.auth(http.HandlerFunc(a.handleContent)))
	mux.Handle("GET /api/books/{id}/cover", a.auth(http.HandlerFunc(a.handleCover)))
	mux.Handle("GET /api/books/{id}/pages", a.auth(http.HandlerFunc(a.handlePages)))
	mux.Handle("GET /api/books/{id}/pages/{page}", a.auth(http.HandlerFunc(a.handlePage)))
	mux.Handle("GET /api/books/{id}/progress", a.auth(http.HandlerFunc(a.handleProgress)))
	mux.Handle("PUT /api/books/{id}/progress", a.auth(http.HandlerFunc(a.handleSaveProgress)))
	mux.Handle("POST /api/books/{id}/reading-time", a.auth(http.HandlerFunc(a.handleReadingTime)))
	mux.HandleFunc("/", a.handleWeb)
	return requestLog(securityHeaders(mux))
}

func (a *app) scan() (int, error) {
	shelves, err := a.store.Shelves()
	if err != nil {
		return 0, err
	}
	return a.library.Scan(shelves...)
}

func (a *app) scanLoop(ctx context.Context) {
	if a.cfg.scanInterval <= 0 {
		return
	}
	ticker := time.NewTicker(a.cfg.scanInterval)
	defer ticker.Stop()
	for {
		select {
		case <-ticker.C:
			if count, err := a.scan(); err != nil {
				slog.Error("library scan failed", "error", err)
			} else {
				slog.Info("library scanned", "books", count)
			}
		case <-ctx.Done():
			return
		}
	}
}

func (a *app) handleServer(w http.ResponseWriter, _ *http.Request) {
	writeJSON(w, http.StatusOK, map[string]any{
		"name":          "微光阅",
		"version":       version,
		"api_version":   4,
		"auth_required": a.cfg.password != "",
		"formats":       []string{"epub", "mobi", "azw3", "pdf", "cbz", "txt"},
	})
}

func (a *app) handleSession(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, map[string]bool{
		"auth_required": a.cfg.password != "",
		"authenticated": a.authenticated(r),
	})
}

func (a *app) handleLogin(w http.ResponseWriter, r *http.Request) {
	if a.cfg.password == "" {
		writeJSON(w, http.StatusOK, map[string]bool{"authenticated": true})
		return
	}
	var input struct {
		Password string `json:"password"`
	}
	if err := decodeJSON(r, &input); err != nil {
		writeError(w, http.StatusBadRequest, err.Error())
		return
	}
	if subtle.ConstantTimeCompare([]byte(input.Password), []byte(a.cfg.password)) != 1 {
		writeError(w, http.StatusUnauthorized, "密码不正确")
		return
	}
	tokenBytes := make([]byte, 32)
	if _, err := rand.Read(tokenBytes); err != nil {
		writeError(w, http.StatusInternalServerError, "无法创建会话")
		return
	}
	token := hex.EncodeToString(tokenBytes)
	expires := time.Now().Add(30 * 24 * time.Hour)
	a.sessions.Lock()
	a.sessions.items[token] = expires
	a.sessions.Unlock()
	http.SetCookie(w, &http.Cookie{
		Name:     "lumos_session",
		Value:    token,
		Path:     "/",
		Expires:  expires,
		MaxAge:   30 * 24 * 60 * 60,
		HttpOnly: true,
		Secure:   r.TLS != nil || r.Header.Get("X-Forwarded-Proto") == "https",
		SameSite: http.SameSiteStrictMode,
	})
	writeJSON(w, http.StatusOK, map[string]bool{"authenticated": true})
}

func (a *app) handleLogout(w http.ResponseWriter, r *http.Request) {
	if cookie, err := r.Cookie("lumos_session"); err == nil {
		a.sessions.Lock()
		delete(a.sessions.items, cookie.Value)
		a.sessions.Unlock()
	}
	http.SetCookie(w, &http.Cookie{Name: "lumos_session", Path: "/", MaxAge: -1, HttpOnly: true})
	w.WriteHeader(http.StatusNoContent)
}

func (a *app) auth(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if !a.authenticated(r) {
			writeError(w, http.StatusUnauthorized, "需要登录")
			return
		}
		next.ServeHTTP(w, r)
	})
}

func (a *app) authenticated(r *http.Request) bool {
	if a.cfg.password == "" {
		return true
	}
	cookie, err := r.Cookie("lumos_session")
	if err != nil {
		return false
	}
	a.sessions.Lock()
	defer a.sessions.Unlock()
	expires, ok := a.sessions.items[cookie.Value]
	if ok && time.Now().Before(expires) {
		return true
	}
	delete(a.sessions.items, cookie.Value)
	return false
}

func (a *app) handleBooks(w http.ResponseWriter, _ *http.Request) {
	progress, err := a.store.AllProgress()
	if err != nil {
		writeError(w, http.StatusInternalServerError, "无法读取进度")
		return
	}
	books := a.library.List()
	for index := range books {
		if saved, ok := progress[books[index].ID]; ok {
			books[index].Progress = saved.Position
			books[index].Locator = saved.Locator
			books[index].ProgressTime = saved.UpdatedAt
		}
	}
	writeJSON(w, http.StatusOK, map[string]any{"books": books, "count": len(books)})
}

func (a *app) handleBook(w http.ResponseWriter, r *http.Request) {
	book, ok := a.book(r)
	if !ok {
		writeError(w, http.StatusNotFound, "书籍不存在")
		return
	}
	writeJSON(w, http.StatusOK, book)
}

func (a *app) handleScan(w http.ResponseWriter, _ *http.Request) {
	started := time.Now()
	count, err := a.scan()
	if err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"count": count, "elapsed_ms": time.Since(started).Milliseconds()})
}

func (a *app) handleShelves(w http.ResponseWriter, _ *http.Request) {
	shelves, err := a.store.Shelves()
	if err != nil {
		writeError(w, http.StatusInternalServerError, "无法读取书架设置")
		return
	}
	directories, err := discoverDirectories(a.cfg.libraryDir)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "无法读取书库目录")
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"shelves": shelves, "directories": directories, "automatic": len(shelves) == 0})
}

func (a *app) handleSaveShelves(w http.ResponseWriter, r *http.Request) {
	var input struct {
		Shelves []Bookshelf `json:"shelves"`
	}
	if err := decodeJSON(r, &input); err != nil {
		writeError(w, http.StatusBadRequest, err.Error())
		return
	}
	if len(input.Shelves) > 32 {
		writeError(w, http.StatusBadRequest, "书架不能超过 32 个")
		return
	}
	names, paths := make(map[string]bool), make(map[string]bool)
	for index := range input.Shelves {
		shelf := &input.Shelves[index]
		shelf.Name = strings.TrimSpace(shelf.Name)
		shelf.Path = filepath.ToSlash(filepath.Clean(filepath.FromSlash(strings.TrimSpace(shelf.Path))))
		if shelf.Kind == "" {
			shelf.Kind = "auto"
		}
		if shelf.Name == "" || len(shelf.Name) > 64 || len(shelf.Path) > 512 {
			writeError(w, http.StatusBadRequest, "书架名称或目录无效")
			return
		}
		if shelf.Kind != "auto" && shelf.Kind != "book" && shelf.Kind != "comic" {
			writeError(w, http.StatusBadRequest, "书架内容类型无效")
			return
		}
		if names[shelf.Name] || paths[shelf.Path] {
			writeError(w, http.StatusBadRequest, "书架名称和目录不能重复")
			return
		}
		if _, err := resolveShelfPath(a.cfg.libraryDir, shelf.Path); err != nil {
			writeError(w, http.StatusBadRequest, err.Error())
			return
		}
		names[shelf.Name], paths[shelf.Path] = true, true
	}
	if err := a.store.ReplaceShelves(input.Shelves); err != nil {
		writeError(w, http.StatusInternalServerError, "无法保存书架设置")
		return
	}
	count, err := a.scan()
	if err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"shelves": input.Shelves, "count": count})
}

func (a *app) handleStats(w http.ResponseWriter, _ *http.Request) {
	stats, err := a.store.ReadingStats(time.Now())
	if err != nil {
		writeError(w, http.StatusInternalServerError, "无法读取阅读数据")
		return
	}
	for index := range stats.Books {
		if book, ok := a.library.Get(stats.Books[index].BookID); ok {
			stats.Books[index].Title = book.Title
		}
	}
	writeJSON(w, http.StatusOK, stats)
}

type fontFile struct {
	Name string `json:"name"`
	URL  string `json:"url"`
	Size int64  `json:"size"`
}

func (a *app) handleFonts(w http.ResponseWriter, _ *http.Request) {
	entries, err := os.ReadDir(a.cfg.fontsDir)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "无法读取字体目录")
		return
	}
	fonts := make([]fontFile, 0, len(entries))
	for _, entry := range entries {
		if entry.IsDir() || !allowedFont(entry.Name()) {
			continue
		}
		info, err := entry.Info()
		if err == nil {
			fonts = append(fonts, fontFile{Name: entry.Name(), URL: "/api/fonts/" + url.PathEscape(entry.Name()), Size: info.Size()})
		}
	}
	slices.SortFunc(fonts, func(a, b fontFile) int { return strings.Compare(a.Name, b.Name) })
	writeJSON(w, http.StatusOK, map[string]any{"fonts": fonts, "count": len(fonts)})
}

func (a *app) handleUploadFont(w http.ResponseWriter, r *http.Request) {
	r.Body = http.MaxBytesReader(w, r.Body, 32<<20)
	file, header, err := r.FormFile("font")
	if err != nil {
		writeError(w, http.StatusBadRequest, "请选择不超过 32 MB 的字体文件")
		return
	}
	defer file.Close()
	name := filepath.Base(strings.ReplaceAll(strings.TrimSpace(header.Filename), "\\", string(filepath.Separator)))
	if name == "." || !allowedFont(name) {
		writeError(w, http.StatusBadRequest, "仅支持 TTF、OTF、WOFF 和 WOFF2 字体")
		return
	}
	var signature [4]byte
	if _, err := io.ReadFull(file, signature[:]); err != nil || !validFontSignature(name, string(signature[:])) {
		writeError(w, http.StatusBadRequest, "字体文件内容无效")
		return
	}
	if _, err := file.Seek(0, io.SeekStart); err != nil {
		writeError(w, http.StatusBadRequest, "字体文件无法读取")
		return
	}
	target := filepath.Join(a.cfg.fontsDir, name)
	output, err := os.OpenFile(target, os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0o640)
	if errors.Is(err, os.ErrExist) {
		writeError(w, http.StatusConflict, "同名字体已存在")
		return
	}
	if err != nil {
		writeError(w, http.StatusInternalServerError, "无法保存字体")
		return
	}
	if _, err := io.Copy(output, file); err != nil {
		_ = output.Close()
		_ = os.Remove(target)
		writeError(w, http.StatusBadRequest, "字体上传失败")
		return
	}
	if err := output.Close(); err != nil {
		writeError(w, http.StatusInternalServerError, "无法保存字体")
		return
	}
	info, _ := os.Stat(target)
	font := fontFile{Name: name, URL: "/api/fonts/" + url.PathEscape(name)}
	if info != nil {
		font.Size = info.Size()
	}
	writeJSON(w, http.StatusCreated, map[string]any{"font": font})
}

func (a *app) handleFont(w http.ResponseWriter, r *http.Request) {
	name := r.PathValue("name")
	if name != filepath.Base(name) || !allowedFont(name) {
		writeError(w, http.StatusNotFound, "字体不存在")
		return
	}
	file, err := os.Open(filepath.Join(a.cfg.fontsDir, name))
	if err != nil {
		writeError(w, http.StatusNotFound, "字体不存在")
		return
	}
	defer file.Close()
	info, err := file.Stat()
	if err != nil || !info.Mode().IsRegular() {
		writeError(w, http.StatusNotFound, "字体不存在")
		return
	}
	w.Header().Set("Content-Type", imageType(name))
	w.Header().Set("Cache-Control", "private, max-age=86400")
	http.ServeContent(w, r, name, info.ModTime(), file)
}

func allowedFont(name string) bool {
	switch strings.ToLower(filepath.Ext(name)) {
	case ".ttf", ".otf", ".woff", ".woff2":
		return true
	default:
		return false
	}
}

func validFontSignature(name, signature string) bool {
	switch strings.ToLower(filepath.Ext(name)) {
	case ".ttf":
		return signature == "\x00\x01\x00\x00" || signature == "true" || signature == "typ1"
	case ".otf":
		return signature == "OTTO"
	case ".woff":
		return signature == "wOFF"
	case ".woff2":
		return signature == "wOF2"
	default:
		return false
	}
}

func (a *app) handleContent(w http.ResponseWriter, r *http.Request) {
	book, ok := a.book(r)
	if !ok {
		writeError(w, http.StatusNotFound, "书籍不存在")
		return
	}
	file, err := http.Dir(filepath.Dir(book.path)).Open(filepath.Base(book.path))
	if err != nil {
		writeError(w, http.StatusNotFound, "文件不可读")
		return
	}
	defer file.Close()
	seeker, ok := file.(io.ReadSeeker)
	if !ok {
		writeError(w, http.StatusInternalServerError, "文件不支持流式读取")
		return
	}
	w.Header().Set("Content-Type", contentType(book.Format))
	w.Header().Set("Cache-Control", "private, no-store")
	w.Header().Set("ETag", fmt.Sprintf(`"%s-%x-%x"`, book.ID, book.Size, book.Modified.UnixNano()))
	http.ServeContent(w, r, filepath.Base(book.path), book.Modified, seeker)
}

func (a *app) handleCover(w http.ResponseWriter, r *http.Request) {
	book, ok := a.book(r)
	if !ok {
		writeError(w, http.StatusNotFound, "书籍不存在")
		return
	}
	if book.Format != "epub" && book.Format != "cbz" {
		writeError(w, http.StatusNotFound, "没有可提取的封面")
		return
	}
	archive, err := zip.OpenReader(book.path)
	if err != nil {
		writeError(w, http.StatusUnprocessableEntity, "无法读取书籍压缩包")
		return
	}
	defer archive.Close()
	var cover *zip.File
	if book.Format == "cbz" {
		if len(book.pageEntries) > 0 {
			cover = findZipFile(archive.File, book.pageEntries[0])
		}
	} else {
		cover = findZipFile(archive.File, book.coverEntry)
	}
	if cover == nil {
		writeError(w, http.StatusNotFound, "未识别到封面")
		return
	}
	serveZipFile(w, r, book, cover, "private, max-age=86400")
}

type comicPage struct {
	Index int    `json:"index"`
	Name  string `json:"name"`
	URL   string `json:"url"`
}

func (a *app) handlePages(w http.ResponseWriter, r *http.Request) {
	book, ok := a.book(r)
	if !ok {
		writeError(w, http.StatusNotFound, "书籍不存在")
		return
	}
	if book.Format != "cbz" {
		writeError(w, http.StatusBadRequest, "这不是漫画书")
		return
	}
	if len(book.pageEntries) == 0 {
		writeError(w, http.StatusUnprocessableEntity, "无法读取漫画页")
		return
	}
	pages := make([]comicPage, len(book.pageEntries))
	for index, entry := range book.pageEntries {
		pages[index] = comicPage{
			Index: index,
			Name:  filepath.Base(entry),
			URL:   fmt.Sprintf("/api/books/%s/pages/%d", book.ID, index),
		}
	}
	w.Header().Set("Cache-Control", "private, no-store")
	writeJSON(w, http.StatusOK, map[string]any{"pages": pages, "count": len(pages)})
}

func (a *app) handlePage(w http.ResponseWriter, r *http.Request) {
	book, ok := a.book(r)
	if !ok {
		writeError(w, http.StatusNotFound, "书籍不存在")
		return
	}
	if book.Format != "cbz" {
		writeError(w, http.StatusBadRequest, "这不是漫画书")
		return
	}
	pageIndex, err := strconv.Atoi(r.PathValue("page"))
	if err != nil || pageIndex < 0 {
		writeError(w, http.StatusBadRequest, "页码无效")
		return
	}
	archive, err := zip.OpenReader(book.path)
	if err != nil {
		writeError(w, http.StatusUnprocessableEntity, "无法读取漫画压缩包")
		return
	}
	defer archive.Close()
	if pageIndex >= len(book.pageEntries) {
		writeError(w, http.StatusNotFound, "漫画页不存在")
		return
	}
	file := findZipFile(archive.File, book.pageEntries[pageIndex])
	if file == nil {
		writeError(w, http.StatusUnprocessableEntity, "漫画页索引已失效，请重新扫描")
		return
	}
	serveZipFile(w, r, book, file, "private, no-store")
}

func serveZipFile(w http.ResponseWriter, r *http.Request, book Book, file *zip.File, cacheControl string) {
	if file.UncompressedSize64 > maxComicPageSize {
		writeError(w, http.StatusRequestEntityTooLarge, "图片超过 64 MB")
		return
	}
	etag := fmt.Sprintf(`"%s-%08x"`, book.ID, file.CRC32)
	if r.Header.Get("If-None-Match") == etag {
		w.WriteHeader(http.StatusNotModified)
		return
	}
	reader, err := file.Open()
	if err != nil {
		writeError(w, http.StatusUnprocessableEntity, "无法解压漫画页")
		return
	}
	defer reader.Close()
	w.Header().Set("Content-Type", imageType(file.Name))
	w.Header().Set("Content-Length", strconv.FormatUint(file.UncompressedSize64, 10))
	w.Header().Set("Cache-Control", cacheControl)
	w.Header().Set("ETag", etag)
	_, _ = io.Copy(w, reader)
}

func comicFiles(files []*zip.File) []*zip.File {
	images := make([]*zip.File, 0, len(files))
	for _, file := range files {
		extension := strings.ToLower(filepath.Ext(file.Name))
		if !file.FileInfo().IsDir() && (extension == ".jpg" || extension == ".jpeg" || extension == ".png" || extension == ".webp" || extension == ".gif") {
			images = append(images, file)
		}
	}
	slices.SortFunc(images, func(a, b *zip.File) int {
		return strings.Compare(naturalKey(a.Name), naturalKey(b.Name))
	})
	return images
}

func comicEntries(filename string) []string {
	archive, err := zip.OpenReader(filename)
	if err != nil {
		return nil
	}
	defer archive.Close()
	files := comicFiles(archive.File)
	entries := make([]string, len(files))
	for index, file := range files {
		entries[index] = file.Name
	}
	return entries
}

func naturalKey(value string) string {
	return digits.ReplaceAllStringFunc(strings.ToLower(value), func(number string) string {
		if len(number) >= 20 {
			return number
		}
		return strings.Repeat("0", 20-len(number)) + number
	})
}

func (a *app) handleProgress(w http.ResponseWriter, r *http.Request) {
	book, ok := a.book(r)
	if !ok {
		writeError(w, http.StatusNotFound, "书籍不存在")
		return
	}
	progress, found, err := a.store.Progress(book.ID)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "无法读取进度")
		return
	}
	if !found {
		progress = Progress{BookID: book.ID}
	}
	writeJSON(w, http.StatusOK, progress)
}

func (a *app) handleSaveProgress(w http.ResponseWriter, r *http.Request) {
	book, ok := a.book(r)
	if !ok {
		writeError(w, http.StatusNotFound, "书籍不存在")
		return
	}
	var input struct {
		Position float64 `json:"position"`
		Locator  string  `json:"locator"`
	}
	if err := decodeJSON(r, &input); err != nil {
		writeError(w, http.StatusBadRequest, err.Error())
		return
	}
	if input.Position < 0 || input.Position > 1 || len(input.Locator) > 2048 {
		writeError(w, http.StatusBadRequest, "阅读进度无效")
		return
	}
	progress := Progress{BookID: book.ID, Position: input.Position, Locator: input.Locator, UpdatedAt: time.Now().UTC()}
	if err := a.store.SaveProgress(progress); err != nil {
		writeError(w, http.StatusInternalServerError, "无法保存进度")
		return
	}
	writeJSON(w, http.StatusOK, progress)
}

func (a *app) handleReadingTime(w http.ResponseWriter, r *http.Request) {
	book, ok := a.book(r)
	if !ok {
		writeError(w, http.StatusNotFound, "书籍不存在")
		return
	}
	var input struct {
		Seconds int `json:"seconds"`
	}
	if err := decodeJSON(r, &input); err != nil {
		writeError(w, http.StatusBadRequest, err.Error())
		return
	}
	if input.Seconds < 1 || input.Seconds > 300 {
		writeError(w, http.StatusBadRequest, "阅读时长无效")
		return
	}
	if err := a.store.AddReadingTime(book.ID, input.Seconds, time.Now()); err != nil {
		writeError(w, http.StatusInternalServerError, "无法保存阅读时长")
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func (a *app) book(r *http.Request) (Book, bool) {
	return a.library.Get(r.PathValue("id"))
}

func (a *app) handleWeb(w http.ResponseWriter, r *http.Request) {
	if strings.HasPrefix(r.URL.Path, "/api/") {
		writeError(w, http.StatusNotFound, "接口不存在")
		return
	}
	requested := strings.TrimPrefix(filepath.ToSlash(filepath.Clean(r.URL.Path)), "/")
	if requested != "." && requested != "" {
		if info, err := fs.Stat(a.web, requested); err == nil && !info.IsDir() {
			http.FileServer(http.FS(a.web)).ServeHTTP(w, r)
			return
		}
	}
	index, err := fs.ReadFile(a.web, "index.html")
	if err != nil {
		http.Error(w, "web app not built", http.StatusInternalServerError)
		return
	}
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.Header().Set("Cache-Control", "no-cache")
	_, _ = w.Write(index)
}

func contentType(format string) string {
	switch format {
	case "epub":
		return "application/epub+zip"
	case "pdf":
		return "application/pdf"
	case "mobi":
		return "application/x-mobipocket-ebook"
	case "azw3":
		return "application/vnd.amazon.ebook"
	case "cbz":
		return "application/vnd.comicbook+zip"
	default:
		return "text/plain; charset=utf-8"
	}
}

func imageType(name string) string {
	if contentType := mime.TypeByExtension(strings.ToLower(filepath.Ext(name))); contentType != "" {
		return contentType
	}
	return "application/octet-stream"
}

func decodeJSON(r *http.Request, target any) error {
	decoder := json.NewDecoder(http.MaxBytesReader(nil, r.Body, 8<<10))
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(target); err != nil {
		return errors.New("请求内容无效")
	}
	return nil
}

func writeJSON(w http.ResponseWriter, status int, value any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(value)
}

func writeError(w http.ResponseWriter, status int, message string) {
	writeJSON(w, status, map[string]string{"error": message})
}

func requestLog(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		started := time.Now()
		next.ServeHTTP(w, r)
		if strings.HasPrefix(r.URL.Path, "/api/") && r.URL.Path != "/api/session" && !strings.HasSuffix(r.URL.Path, "/content") && !strings.HasSuffix(r.URL.Path, "/progress") && !strings.HasSuffix(r.URL.Path, "/reading-time") {
			slog.Info("request", "method", r.Method, "path", r.URL.Path, "elapsed", time.Since(started).Round(time.Millisecond))
		}
	})
}

func securityHeaders(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("X-Content-Type-Options", "nosniff")
		w.Header().Set("Referrer-Policy", "no-referrer")
		w.Header().Set("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline' blob:; img-src 'self' data: blob:; font-src 'self' blob:; connect-src 'self' blob:; worker-src 'self' blob:; frame-src 'self' blob:; object-src 'none'; base-uri 'none'; frame-ancestors 'self'")
		next.ServeHTTP(w, r)
	})
}
