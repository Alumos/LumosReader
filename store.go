package main

import (
	"database/sql"
	"fmt"
	"time"

	_ "modernc.org/sqlite"
)

type Progress struct {
	BookID    string    `json:"book_id"`
	Position  float64   `json:"position"`
	Locator   string    `json:"locator,omitempty"`
	UpdatedAt time.Time `json:"updated_at,omitzero"`
}

type Bookshelf struct {
	Name string `json:"name"`
	Path string `json:"path"`
	Kind string `json:"kind"`
}

type ReadingDay struct {
	Date    string `json:"date"`
	Seconds int    `json:"seconds"`
}

type BookReading struct {
	BookID  string `json:"book_id"`
	Title   string `json:"title,omitempty"`
	Seconds int    `json:"seconds"`
}

type ReadingStats struct {
	TotalSeconds int           `json:"total_seconds"`
	TodaySeconds int           `json:"today_seconds"`
	Days         []ReadingDay  `json:"days"`
	Books        []BookReading `json:"books"`
}

type store struct {
	db *sql.DB
}

func openStore(path string) (*store, error) {
	db, err := sql.Open("sqlite", "file:"+path+"?_pragma=busy_timeout(5000)&_pragma=journal_mode(WAL)")
	if err != nil {
		return nil, err
	}
	db.SetMaxOpenConns(1)
	if _, err := db.Exec(`CREATE TABLE IF NOT EXISTS progress (
		book_id TEXT PRIMARY KEY,
		position REAL NOT NULL,
		locator TEXT NOT NULL DEFAULT '',
		updated_at TEXT NOT NULL
	);
	CREATE TABLE IF NOT EXISTS shelves (
		name TEXT PRIMARY KEY,
		path TEXT NOT NULL UNIQUE,
		kind TEXT NOT NULL DEFAULT 'auto',
		sort_order INTEGER NOT NULL
	);
	CREATE TABLE IF NOT EXISTS reading_time (
		book_id TEXT NOT NULL,
		day TEXT NOT NULL,
		seconds INTEGER NOT NULL,
		PRIMARY KEY (book_id, day)
	)`); err != nil {
		db.Close()
		return nil, err
	}
	var hasShelfKind int
	if err := db.QueryRow(`SELECT COUNT(*) FROM pragma_table_info('shelves') WHERE name = 'kind'`).Scan(&hasShelfKind); err != nil {
		db.Close()
		return nil, err
	}
	if hasShelfKind == 0 {
		if _, err := db.Exec(`ALTER TABLE shelves ADD COLUMN kind TEXT NOT NULL DEFAULT 'auto'`); err != nil {
			db.Close()
			return nil, err
		}
	}
	return &store{db: db}, nil
}

func (s *store) Close() error {
	return s.db.Close()
}

func (s *store) AllProgress() (map[string]Progress, error) {
	rows, err := s.db.Query(`SELECT book_id, position, locator, updated_at FROM progress`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	items := make(map[string]Progress)
	for rows.Next() {
		progress, err := scanProgress(rows)
		if err != nil {
			return nil, err
		}
		items[progress.BookID] = progress
	}
	return items, rows.Err()
}

func (s *store) Progress(bookID string) (Progress, bool, error) {
	progress, err := scanProgress(s.db.QueryRow(`SELECT book_id, position, locator, updated_at FROM progress WHERE book_id = ?`, bookID))
	if err == sql.ErrNoRows {
		return Progress{}, false, nil
	}
	return progress, err == nil, err
}

func (s *store) SaveProgress(progress Progress) error {
	_, err := s.db.Exec(`INSERT INTO progress (book_id, position, locator, updated_at) VALUES (?, ?, ?, ?)
		ON CONFLICT(book_id) DO UPDATE SET position = excluded.position, locator = excluded.locator, updated_at = excluded.updated_at`,
		progress.BookID, progress.Position, progress.Locator, progress.UpdatedAt.Format(time.RFC3339Nano))
	return err
}

func (s *store) Shelves() ([]Bookshelf, error) {
	rows, err := s.db.Query(`SELECT name, path, kind FROM shelves ORDER BY sort_order`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var shelves []Bookshelf
	for rows.Next() {
		var shelf Bookshelf
		if err := rows.Scan(&shelf.Name, &shelf.Path, &shelf.Kind); err != nil {
			return nil, err
		}
		shelves = append(shelves, shelf)
	}
	return shelves, rows.Err()
}

func (s *store) ReplaceShelves(shelves []Bookshelf) error {
	tx, err := s.db.Begin()
	if err != nil {
		return err
	}
	defer tx.Rollback()
	if _, err := tx.Exec(`DELETE FROM shelves`); err != nil {
		return err
	}
	for index, shelf := range shelves {
		if _, err := tx.Exec(`INSERT INTO shelves (name, path, kind, sort_order) VALUES (?, ?, ?, ?)`, shelf.Name, shelf.Path, shelf.Kind, index); err != nil {
			return err
		}
	}
	return tx.Commit()
}

func (s *store) AddReadingTime(bookID string, seconds int, now time.Time) error {
	_, err := s.db.Exec(`INSERT INTO reading_time (book_id, day, seconds) VALUES (?, ?, ?)
		ON CONFLICT(book_id, day) DO UPDATE SET seconds = seconds + excluded.seconds`,
		bookID, now.Format(time.DateOnly), seconds)
	return err
}

func (s *store) ReadingStats(now time.Time) (ReadingStats, error) {
	var stats ReadingStats
	if err := s.db.QueryRow(`SELECT COALESCE(SUM(seconds), 0), COALESCE(SUM(CASE WHEN day = ? THEN seconds ELSE 0 END), 0) FROM reading_time`, now.Format(time.DateOnly)).Scan(&stats.TotalSeconds, &stats.TodaySeconds); err != nil {
		return stats, err
	}
	rows, err := s.db.Query(`SELECT day, SUM(seconds) FROM reading_time WHERE day >= ? GROUP BY day ORDER BY day`, now.AddDate(0, 0, -6).Format(time.DateOnly))
	if err != nil {
		return stats, err
	}
	for rows.Next() {
		var day ReadingDay
		if err := rows.Scan(&day.Date, &day.Seconds); err != nil {
			rows.Close()
			return stats, err
		}
		stats.Days = append(stats.Days, day)
	}
	if err := rows.Close(); err != nil {
		return stats, err
	}
	rows, err = s.db.Query(`SELECT book_id, SUM(seconds) FROM reading_time GROUP BY book_id ORDER BY SUM(seconds) DESC LIMIT 8`)
	if err != nil {
		return stats, err
	}
	defer rows.Close()
	for rows.Next() {
		var book BookReading
		if err := rows.Scan(&book.BookID, &book.Seconds); err != nil {
			return stats, err
		}
		stats.Books = append(stats.Books, book)
	}
	return stats, rows.Err()
}

type rowScanner interface {
	Scan(...any) error
}

func scanProgress(row rowScanner) (Progress, error) {
	var progress Progress
	var updatedAt string
	if err := row.Scan(&progress.BookID, &progress.Position, &progress.Locator, &updatedAt); err != nil {
		return Progress{}, err
	}
	parsed, err := time.Parse(time.RFC3339Nano, updatedAt)
	if err != nil {
		// Compatibility with rows written by the initial prototype.
		parsed, err = time.Parse("2006-01-02 15:04:05.999999999 -0700 MST", updatedAt)
	}
	if err != nil {
		return Progress{}, fmt.Errorf("parse progress timestamp: %w", err)
	}
	progress.UpdatedAt = parsed
	return progress, nil
}
