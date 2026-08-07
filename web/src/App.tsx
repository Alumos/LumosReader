import {
  BookOpen,
  ChevronDown,
  ChevronRight,
  Clock3,
  Folder,
  Library,
  LoaderCircle,
  LogIn,
  PanelsTopLeft,
  RefreshCw,
  Search,
  Sparkles,
  X,
} from "lucide-react";
import { CSSProperties, FormEvent, useCallback, useEffect, useMemo, useState } from "react";
import { AccountMenu, AccountPanel, UserPanel } from "./AccountPanels";
import { BookCover } from "./Cover";
import { Reader } from "./Reader";
import { Book, ServerInfo, api, fileStem } from "./model";
import { applyAppTheme, loadAppTheme } from "./theme";

type Section =
  | { kind: "all" }
  | { kind: "recent" }
  | { kind: "collection"; shelfKind: Book["shelf_kind"] }
  | { kind: "shelf"; shelfKind: Book["shelf_kind"]; shelf: string; category?: string };
function App() {
  const [theme, setTheme] = useState(loadAppTheme);
  const [server, setServer] = useState<ServerInfo>();
  const [authenticated, setAuthenticated] = useState(false);
  const [books, setBooks] = useState<Book[]>([]);
  const [query, setQuery] = useState("");
  const [section, setSection] = useState<Section>({ kind: "all" });
  const [reading, setReading] = useState<Book>();
  const [seriesOpen, setSeriesOpen] = useState<Book[]>();
  const [panel, setPanel] = useState<AccountPanel>();
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const recent = useMemo(() => books
    .filter((book) => book.progress_time)
    .sort((a, b) => (b.progress_time ?? "").localeCompare(a.progress_time ?? "")), [books]);

  useEffect(() => {
    applyAppTheme(theme);
    localStorage.setItem("lumos-app-theme", JSON.stringify(theme));
  }, [theme]);

  const loadBooks = useCallback(async () => {
    const result = await api<{ books: Book[] }>("/api/books");
    setBooks(result.books);
  }, []);

  useEffect(() => {
    Promise.all([
      api<ServerInfo>("/api/server"),
      api<{ authenticated: boolean }>("/api/session"),
    ])
      .then(([serverInfo, session]) => {
        setServer(serverInfo);
        setAuthenticated(session.authenticated);
        if (session.authenticated) return loadBooks();
      })
      .catch((reason) => setError(reason.message))
      .finally(() => setLoading(false));
  }, [loadBooks]);

  if (loading) return <Loading />;
  if (!authenticated) {
    return <Login server={server} error={error} onLogin={async (password) => {
      setError("");
      try {
        await api("/api/session", { method: "POST", body: JSON.stringify({ password }) });
        setAuthenticated(true);
        await loadBooks();
      } catch (reason) {
        setError(reason instanceof Error ? reason.message : "登录失败");
      }
    }} />;
  }

  if (reading) {
    const collection = books.filter((book) => book.series && book.series === reading.series && book.shelf === reading.shelf && book.category === reading.category);
    return <Reader book={reading} collection={collection} onBook={setReading} onClose={() => {
      setReading(undefined);
      loadBooks().catch(() => undefined);
    }} />;
  }

  const normalized = query.trim().toLocaleLowerCase();
  const source = section.kind === "recent" ? recent : section.kind === "collection"
    ? books.filter((book) => book.shelf_kind === section.shelfKind)
    : section.kind === "shelf"
    ? books.filter((book) => book.shelf_kind === section.shelfKind && book.shelf === section.shelf && (!section.category || book.category === section.category))
    : books;
  const visibleBooks = source.filter((book) =>
    `${book.title} ${book.author ?? ""} ${book.series ?? ""}`.toLocaleLowerCase().includes(normalized),
  );
  const { series, standalone } = groupSeries(visibleBooks);

  return (
    <div className="app-shell">
      <aside className="sidebar">
        <Brand />
        <Navigation books={books} section={section} onChange={setSection} />
        <AccountMenu onOpen={setPanel} />
      </aside>

      <main className="main-content">
        <header className="topbar">
          <div className="mobile-brand"><Brand /></div>
          <div className="search-box">
            <Search size={18} />
            <input aria-label="搜索书库" value={query} onChange={(event) => setQuery(event.target.value)} placeholder="搜索书名、作者或系列" />
            {query && <button aria-label="清空搜索" onClick={() => setQuery("")}><X size={16} /></button>}
          </div>
          <button className="button secondary icon-label" onClick={async () => {
            setLoading(true);
            try {
              await api("/api/scan", { method: "POST" });
              await loadBooks();
            } finally {
              setLoading(false);
            }
          }}><RefreshCw size={16} />重新扫描</button>
          <div className="mobile-account"><AccountMenu onOpen={setPanel} /></div>
          <div className="mobile-tabs"><Navigation books={books} section={section} onChange={setSection} compact /></div>
        </header>

        <div key={sectionKey(section)} className="page-view">
          {section.kind === "all" && (
            <section className="hero">
              <div>
                <span className="eyebrow"><Sparkles size={14} /> 你的私人阅读空间</span>
                <h1>把书留在 NAS，<br />把阅读带在身边。</h1>
                <p>无需整本下载，只取此刻要读的章节和页面。</p>
              </div>
              <RecentReads books={recent.slice(0, 3)} onOpen={setReading} />
            </section>
          )}

          <section className={`library-section ${section.kind !== "all" ? "recent-section" : ""}`}>
            <div className="section-heading">
              <div><p>{sectionLabel(section)}</p><h2>{heading(section, normalized, series.length + standalone.length)}</h2></div>
              <span>服务端 v{server?.version}</span>
            </div>
            {visibleBooks.length ? <div className="book-grid">
              {series.map((group, index) => <SeriesShelf key={seriesKey(group[0])} books={group} index={index} onOpen={() => setSeriesOpen(books.filter((book) => seriesKey(book) === seriesKey(group[0])))} />)}
              {standalone.map((book, index) => <BookCard key={book.id} book={book} index={series.length + index} onOpen={() => setReading(book)} />)}
            </div> : (
              <div className="empty-library"><BookOpen size={32} /><h3>{section.kind === "recent" ? "还没有阅读记录" : "这里还没有书"}</h3><p>{section.kind === "recent" ? "打开一本书后，它会出现在这里。" : "检查书架目录后重新扫描。"}</p></div>
            )}
          </section>
        </div>
      </main>

      {panel && <UserPanel kind={panel} server={server} theme={theme} onTheme={setTheme} onClose={() => setPanel(undefined)} onSaved={loadBooks} />}
      {seriesOpen && <VolumePicker books={seriesOpen} onClose={() => setSeriesOpen(undefined)} onOpen={(book) => { setSeriesOpen(undefined); setReading(book); }} />}
    </div>
  );
}

function RecentReads({ books, onOpen }: { books: Book[]; onOpen: (book: Book) => void }) {
  if (!books.length) return <div className="recent-reading empty"><BookOpen size={28} /><strong>从一本书开始</strong><small>阅读进度会在设备间同步</small></div>;
  return <aside className="recent-reading" aria-label="最近阅读">
    <header><div><span>最近阅读</span><strong>接着上次的位置</strong></div><small>滑动查看</small></header>
    <div className="recent-reading-scroll">
      {books.map((book, index) => <button key={book.id} className="recent-reading-card" onClick={() => onOpen(book)}>
        <div className={`cover recent-reading-cover ${coverColor(index)}`}><BookCover book={book} /><span>{book.title.slice(0, 1)}</span><small>{book.format.toUpperCase()}</small></div>
        <div><strong title={book.title}>{book.title}</strong><small>{Math.round(book.progress * 100)}% · {book.author || book.format.toUpperCase()}</small>
          <div className="progress thin"><i style={{ width: `${book.progress * 100}%` }} /></div>
        </div>
      </button>)}
    </div>
  </aside>;
}

function Navigation({ books, section, onChange, compact = false }: { books: Book[]; section: Section; onChange: (section: Section) => void; compact?: boolean }) {
  const tree = useMemo(() => shelfTree(books), [books]);
  const [expanded, setExpanded] = useState<Set<string>>(() => new Set(section.kind === "shelf" ? [`${section.shelfKind}/${section.shelf}`] : []));
  const toggle = (key: string) => setExpanded((current) => {
    const next = new Set(current);
    if (next.has(key)) next.delete(key); else next.add(key);
    return next;
  });
  const groups = [
    { kind: "comic" as const, label: "漫画", Icon: PanelsTopLeft },
    { kind: "book" as const, label: "图书", Icon: BookOpen },
  ];
  return <nav>
    <button className={`nav-item ${section.kind === "all" ? "active" : ""}`} onClick={() => onChange({ kind: "all" })}><Library size={18} />浏览全库</button>
    <button className={`nav-item ${section.kind === "recent" ? "active" : ""}`} onClick={() => onChange({ kind: "recent" })}><Clock3 size={18} />最近阅读</button>
    {compact && groups.map(({ kind, label, Icon }) => <button key={kind} className={`nav-item library-kind ${section.kind === "collection" && section.shelfKind === kind ? "active" : ""}`} onClick={() => onChange({ kind: "collection", shelfKind: kind })}><Icon size={18} />{label}</button>)}
    {!compact && <div className="shelf-navigation">
      <small>内容书架</small>
      {groups.map(({ kind, label, Icon }) => <div key={kind} className={`library-group ${kind}`}>
        <button className={`nav-item library-kind ${section.kind === "collection" && section.shelfKind === kind ? "active" : ""}`} onClick={() => onChange({ kind: "collection", shelfKind: kind })}><Icon size={18} /><span>{label}</span><small>{tree[kind].length}</small></button>
        {tree[kind].map(({ shelf, categories }) => {
          const key = `${kind}/${shelf}`;
          return <div key={key} className="shelf-branch">
            <button className={`nav-item ${section.kind === "shelf" && section.shelfKind === kind && section.shelf === shelf && !section.category ? "active" : ""}`} aria-expanded={expanded.has(key)} onClick={() => {
              onChange({ kind: "shelf", shelfKind: kind, shelf });
              toggle(key);
            }}><Folder size={16} /><span>{shelf}</span>{categories.length > 0 && (expanded.has(key) ? <ChevronDown size={14} /> : <ChevronRight size={14} />)}</button>
            {expanded.has(key) && categories.map((category) => <button key={category} className={`nav-item category ${section.kind === "shelf" && section.shelfKind === kind && section.shelf === shelf && section.category === category ? "active" : ""}`} onClick={() => onChange({ kind: "shelf", shelfKind: kind, shelf, category })}><span>{category}</span></button>)}
          </div>;
        })}
      </div>)}
    </div>}
  </nav>;
}

function SeriesShelf({ books, index, onOpen }: { books: Book[]; index: number; onOpen: () => void }) {
  const ordered = [...books].sort((a, b) => a.file_name.localeCompare(b.file_name, undefined, { numeric: true }));
  const recent = ordered.filter((book) => book.progress_time).sort((a, b) => (b.progress_time ?? "").localeCompare(a.progress_time ?? ""))[0];
  const representative = recent ?? ordered[0];
  return <button className="book-card series-book-card" onClick={onOpen} style={{ "--delay": `${Math.min(index, 12) * 35}ms` } as CSSProperties}>
    <div className="series-stack"><div className={`cover ${coverColor(index)}`}><BookCover book={representative} /><span>{representative.series?.slice(0, 1)}</span><small>{ordered.length} 卷</small></div></div>
    <div className="book-meta"><h3 title={representative.series}>{representative.series}</h3><p>{ordered.length} 卷 · {recent ? "继续上次阅读" : representative.category || representative.shelf}</p>{representative.progress > 0 && <div className="progress thin"><i style={{ width: `${representative.progress * 100}%` }} /></div>}</div>
  </button>;
}

function VolumePicker({ books, onClose, onOpen }: { books: Book[]; onClose: () => void; onOpen: (book: Book) => void }) {
  const ordered = [...books].sort((a, b) => a.file_name.localeCompare(b.file_name, undefined, { numeric: true }));
  const latest = ordered.filter((book) => book.progress_time).sort((a, b) => (b.progress_time ?? "").localeCompare(a.progress_time ?? ""))[0];
  return <div className="modal-backdrop" onMouseDown={(event) => event.target === event.currentTarget && onClose()}>
    <section className="user-panel volume-picker" role="dialog" aria-modal="true" aria-label="选择漫画卷册">
      <header><div><small>{ordered[0].series}</small><h2>选择卷册</h2></div><button aria-label="关闭" onClick={onClose}><X size={18} /></button></header>
      <div className="volume-picker-grid">{ordered.map((book, index) => <button key={book.id} className="volume-choice" onClick={() => onOpen(book)}>
        <div className="volume-cover"><BookCover book={book} /><span>{index + 1}</span></div>
        <div className="volume-details"><strong>{fileStem(book.file_name)}</strong>
          {latest?.id === book.id ? <small className="last-read">上次读到 · {Math.round(book.progress * 100)}%</small> : book.progress_time ? <small>已读 · {Math.round(book.progress * 100)}%</small> : <small>尚未阅读</small>}
        </div>
      </button>)}</div>
    </section>
  </div>;
}

function BookCard({ book, index, onOpen }: { book: Book; index: number; onOpen: () => void }) {
  return <button className="book-card" onClick={onOpen} style={{ "--delay": `${Math.min(index, 12) * 35}ms` } as CSSProperties}>
    <div className={`cover ${coverColor(index)}`}><BookCover book={book} /><span>{book.title.slice(0, 1)}</span><small>{book.format.toUpperCase()}</small></div>
    <div className="book-meta"><h3 title={book.title}>{book.title}</h3><p>{book.author || formatSize(book.size)}</p>{book.progress > 0 && <div className="progress thin"><i style={{ width: `${book.progress * 100}%` }} /></div>}</div>
  </button>;
}

function Brand() {
  return <div className="brand"><span><Sparkles size={19} /></span><div><strong>微光阅</strong><small>Lumos Reader</small></div></div>;
}

function Loading() {
  return <div className="loading"><LoaderCircle className="spin" /><span>正在点亮书库…</span></div>;
}

function Login({ server, error, onLogin }: { server?: ServerInfo; error: string; onLogin: (password: string) => Promise<void> }) {
  const [password, setPassword] = useState("");
  const [busy, setBusy] = useState(false);
  const submit = async (event: FormEvent) => {
    event.preventDefault();
    setBusy(true);
    try { await onLogin(password); } finally { setBusy(false); }
  };
  return <div className="login-page"><div className="login-glow" /><form className="login-card" onSubmit={submit}>
    <Brand /><div className="login-copy"><h1>欢迎回来</h1><p>连接到 {server?.name ?? "你的私人书库"}</p></div>
    <label>访问密码<input autoFocus type="password" value={password} onChange={(event) => setPassword(event.target.value)} placeholder="输入服务端密码" /></label>
    {error && <p className="error">{error}</p>}
    <button className="button primary" disabled={busy}><LogIn size={17} />{busy ? "正在连接…" : "进入书库"}</button>
    <small>客户端只保存服务端地址，书籍始终留在 NAS。</small>
  </form></div>;
}

function shelfTree(books: Book[]) {
  const shelves = { book: new Map<string, Set<string>>(), comic: new Map<string, Set<string>>() };
  for (const book of books) {
    const group = shelves[book.shelf_kind];
    const categories = group.get(book.shelf) ?? new Set<string>();
    if (book.category) categories.add(book.category);
    group.set(book.shelf, categories);
  }
  const sorted = (group: Map<string, Set<string>>) => [...group].sort(([a], [b]) => a.localeCompare(b, "zh-CN")).map(([shelf, categories]) => ({ shelf, categories: [...categories].sort((a, b) => a.localeCompare(b, "zh-CN")) }));
  return { book: sorted(shelves.book), comic: sorted(shelves.comic) };
}

function groupSeries(books: Book[]) {
  const groups = new Map<string, Book[]>();
  const standalone: Book[] = [];
  for (const book of books) {
    if (book.shelf_kind !== "comic" || !book.series) standalone.push(book);
    else groups.set(seriesKey(book), [...(groups.get(seriesKey(book)) ?? []), book]);
  }
  return { series: [...groups.values()], standalone };
}

function seriesKey(book: Book) {
  return `${book.shelf_kind}/${book.shelf}/${book.category ?? ""}/${book.series ?? ""}`;
}

function sectionKey(section: Section) {
  if (section.kind === "collection") return `${section.kind}/${section.shelfKind}`;
  return section.kind === "shelf" ? `${section.kind}/${section.shelfKind}/${section.shelf}/${section.category ?? ""}` : section.kind;
}

function sectionLabel(section: Section) {
  if (section.kind === "recent") return "阅读足迹";
  if (section.kind === "collection") return section.shelfKind === "comic" ? "漫画" : "图书";
  if (section.kind === "shelf") return section.category ? `${section.shelf} / ${section.category}` : section.shelf;
  return "全部书籍";
}

function heading(section: Section, searching: string, works: number) {
  if (searching) return `找到 ${works} 部作品`;
  if (section.kind === "recent") return `${works} 部最近读过`;
  return `${works} 部作品`;
}

function coverColor(index: number) {
  return ["sage", "ink", "sand", "mist"][index % 4];
}

function formatSize(bytes: number) {
  if (bytes < 1024 * 1024) return `${Math.max(1, Math.round(bytes / 1024))} KB`;
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
}

function formatDuration(seconds: number) {
  if (seconds < 60) return `${seconds} 秒`;
  const hours = Math.floor(seconds / 3600);
  const minutes = Math.floor(seconds % 3600 / 60);
  return hours ? `${hours} 小时 ${minutes} 分` : `${minutes} 分钟`;
}

export default App;
