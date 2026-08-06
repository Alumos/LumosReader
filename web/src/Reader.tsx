import {
  ArrowLeft,
  BookOpen,
  ChevronLeft,
  ChevronRight,
  ListTree,
  LoaderCircle,
  Save,
  Settings2,
  Trash2,
  X,
} from "lucide-react";
import pdfWorker from "pdfjs-dist/build/pdf.worker.min.mjs?url";
import { CSSProperties, FormEvent, useCallback, useEffect, useMemo, useRef, useState } from "react";
import { Book, ComicPage, RemoteFile, ServerFont, addReadingTime, api, contentURL, fileStem, saveProgress } from "./model";

type LegacyTheme = "paper" | "clean" | "eink" | "night";
type ReadingSettings = {
  template: string;
  backgroundColor: string;
  textColor: string;
  font: "book" | "serif" | "sans" | "system" | "custom";
  fontFile: string;
  fontSize: number;
  lineHeight: number;
  paragraphSpacing: number;
  letterSpacing: number;
  columns: 1 | 2;
  animation: "none" | "slide" | "fade";
  readingDirection: "auto" | "ltr" | "rtl";
  showPageButtons: boolean;
};
type ReadingTemplate = {
  id: string;
  name: string;
  hint: string;
  settings: Omit<ReadingSettings, "template">;
  custom?: boolean;
};
type Chapter = { label: string; href: string; level: number };
type ChromeItem =
  | { kind: "volume"; book: Book; label: string; number: number }
  | { kind: "chapter"; chapter: Chapter; label: string; number: number };
type ReaderControls = {
  previous: () => void;
  next: () => void;
  seek?: (progress: number) => void;
  chapters?: Chapter[];
  goToChapter?: (href: string) => void;
};

const comicPreloadPages = 20;
const previousPageKeys = new Set(["ArrowLeft", "AudioVolumeUp", "VolumeUp"]);
const nextPageKeys = new Set(["ArrowRight", "AudioVolumeDown", "VolumeDown"]);
const comicWindowEnd = (current: number, total: number, visible = 1) => Math.min(total, current + visible + comicPreloadPages);
if (import.meta.env.DEV) console.assert(comicWindowEnd(0, 100) === 21 && comicWindowEnd(95, 100, 2) === 100, "漫画预加载窗口异常");
const objectWithGroupBy = Object as ObjectConstructor & { groupBy?: (items: Iterable<unknown>, key: (item: unknown, index: number) => PropertyKey) => Record<PropertyKey, unknown[]> };
const mapWithGroupBy = Map as MapConstructor & { groupBy?: (items: Iterable<unknown>, key: (item: unknown, index: number) => unknown) => Map<unknown, unknown[]> };

function ensureGroupBy() {
  if (typeof objectWithGroupBy.groupBy !== "function") Object.defineProperty(Object, "groupBy", { configurable: true, writable: true, value(items: Iterable<unknown>, key: (item: unknown, index: number) => PropertyKey) {
    const groups: Record<PropertyKey, unknown[]> = Object.create(null);
    let index = 0;
    for (const item of items) (groups[key(item, index++)] ??= []).push(item);
    return groups;
  } });
  if (typeof mapWithGroupBy.groupBy !== "function") Object.defineProperty(Map, "groupBy", { configurable: true, writable: true, value(items: Iterable<unknown>, key: (item: unknown, index: number) => unknown) {
    const groups = new Map<unknown, unknown[]>();
    let index = 0;
    for (const item of items) {
      const group = key(item, index++);
      const values = groups.get(group);
      if (values) values.push(item); else groups.set(group, [item]);
    }
    return groups;
  } });
}

ensureGroupBy();
if (import.meta.env.DEV) console.assert(objectWithGroupBy.groupBy!([1, 2], (value) => Number(value) % 2)[0]?.[0] === 2, "groupBy 兼容层异常");

const defaultSettings: ReadingSettings = {
  template: "literary",
  backgroundColor: "#f7f1e4",
  textColor: "#29261f",
  font: "serif",
  fontFile: "",
  fontSize: 19,
  lineHeight: 1.8,
  paragraphSpacing: 0.8,
  letterSpacing: 0,
  columns: 1,
  animation: "slide",
  readingDirection: "auto",
  showPageButtons: true,
};

const builtInTemplates: ReadingTemplate[] = [
  { id: "web", name: "网文阅读", hint: "大字舒展", settings: { ...withoutTemplate(defaultSettings), backgroundColor: "#ffffff", textColor: "#202522", fontSize: 21, lineHeight: 1.95, paragraphSpacing: 1, letterSpacing: 0.02 } },
  { id: "literary", name: "精品文学", hint: "纸感留白", settings: withoutTemplate(defaultSettings) },
  { id: "eink", name: "墨水屏", hint: "纯黑无动画", settings: { ...withoutTemplate(defaultSettings), backgroundColor: "#ffffff", textColor: "#000000", fontSize: 20, lineHeight: 1.75, animation: "none" } },
  { id: "comic", name: "漫画优化", hint: "日漫右翻", settings: { ...withoutTemplate(defaultSettings), backgroundColor: "#ffffff", textColor: "#000000", columns: 2, animation: "none", readingDirection: "rtl" } },
  { id: "night", name: "夜间阅读", hint: "低亮深色", settings: { ...withoutTemplate(defaultSettings), backgroundColor: "#161a18", textColor: "#d9ddd9", lineHeight: 1.85 } },
];

export function Reader({ book, collection, onBook, onClose }: { book: Book; collection: Book[]; onBook: (book: Book) => void; onClose: () => void }) {
  const [progress, setProgress] = useState(book.progress);
  const [settings, setSettings] = useState(loadSettings);
  const [customTemplates, setCustomTemplates] = useState(loadTemplates);
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [chromeOpen, setChromeOpen] = useState(false);
  const [immersive, setImmersive] = useState(false);
  const [controls, setControls] = useState<ReaderControls>();
  const turns = useRef(0);
  const styleable = !book.is_comic && (book.format === "epub" || book.format === "mobi" || book.format === "azw3" || book.format === "txt");
  const direction = settings.readingDirection === "auto" ? book.page_direction ?? "ltr" : settings.readingDirection;
  const customFontURL = settings.font === "custom" && settings.fontFile ? `/api/fonts/${encodeURIComponent(settings.fontFile)}` : "";
  const connectControls = useCallback((value?: ReaderControls) => setControls(value), []);
  const onTurn = useCallback(() => {
    if (++turns.current < 2) return;
    setImmersive(true);
    setChromeOpen(false);
    setSettingsOpen(false);
  }, []);

  useReadingTimer(book.id);
  useEffect(() => {
    setProgress(book.progress);
    setChromeOpen(false);
    setSettingsOpen(false);
    setImmersive(false);
    turns.current = 0;
  }, [book]);
  useEffect(() => { localStorage.setItem("lumos-reading-settings", JSON.stringify(settings)); }, [settings]);
  useEffect(() => { localStorage.setItem("lumos-reading-templates", JSON.stringify(customTemplates)); }, [customTemplates]);
  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if (!controls || event.target instanceof HTMLInputElement || event.target instanceof HTMLSelectElement) return;
      const action = previousPageKeys.has(event.key) ? "previous" : nextPageKeys.has(event.key) ? "next" : undefined;
      if (!action) return;
      event.preventDefault();
      controls[action]();
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [controls]);

  return <div className={`reader ${immersive && !chromeOpen && !settingsOpen ? "immersive" : ""}`} style={{ "--reader-bg": settings.backgroundColor, "--reader-fg": settings.textColor } as CSSProperties}>
    <header className="reader-bar">
      <button onClick={onClose}><ArrowLeft size={19} /><span>返回书库</span></button>
      <div><strong>{book.title}</strong><small>{book.format.toUpperCase()}</small></div>
      <div className="reader-actions"><span>{Math.round(progress * 100)}%</span><button aria-label="阅读设置" className={settingsOpen ? "active" : ""} onClick={() => { setChromeOpen(false); setSettingsOpen((open) => !open); }}><Settings2 size={18} /></button></div>
    </header>
    <div className="reader-body">
      {book.format === "cbz" && <ComicReader book={book} settings={settings} direction={direction} onCenter={() => setChromeOpen((open) => !open)} onTurn={onTurn} onControls={connectControls} onProgress={setProgress} />}
      {book.format === "txt" && <TextReader book={book} settings={settings} customFontURL={customFontURL} onCenter={() => setChromeOpen((open) => !open)} onTurn={onTurn} onControls={connectControls} onProgress={setProgress} />}
      {(book.format === "epub" || book.format === "mobi" || book.format === "azw3") && <ReflowReader book={book} settings={settings} customFontURL={customFontURL} onCenter={() => setChromeOpen((open) => !open)} onTurn={onTurn} onControls={connectControls} onProgress={setProgress} />}
      {book.format === "pdf" && <PDFReader book={book} settings={settings} onCenter={() => setChromeOpen((open) => !open)} onTurn={onTurn} onControls={connectControls} onProgress={setProgress} />}
    </div>
    {chromeOpen && <ReaderChrome book={book} collection={collection} progress={progress} controls={controls} onBook={onBook} onClose={() => setChromeOpen(false)} />}
    {settingsOpen && <SettingsPanel
      settings={settings}
      templates={[...builtInTemplates, ...customTemplates]}
      styleable={styleable}
      comic={book.is_comic}
      onChange={setSettings}
      onClose={() => setSettingsOpen(false)}
      onDelete={(id) => setCustomTemplates((templates) => templates.filter((template) => template.id !== id))}
      onSave={(name) => {
        const id = `custom-${Date.now()}`;
        setCustomTemplates((templates) => [...templates, { id, name, hint: "我的排版", settings: withoutTemplate(settings), custom: true }]);
        setSettings((current) => ({ ...current, template: id }));
      }}
      onFont={(fontFile) => setSettings((current) => ({ ...current, font: "custom", fontFile }))}
    />}
  </div>;
}

function ReaderChrome({ book, collection, progress, controls, onBook, onClose }: { book: Book; collection: Book[]; progress: number; controls?: ReaderControls; onBook: (book: Book) => void; onClose: () => void }) {
  const [query, setQuery] = useState("");
  const [descending, setDescending] = useState(false);
  const [page, setPage] = useState(0);
  const volumes = collection.length > 1 ? [...collection].sort((a, b) => a.file_name.localeCompare(b.file_name, undefined, { numeric: true })) : [];
  const chapters = controls?.chapters ?? [];
  const normalized = query.trim().toLocaleLowerCase();
  const filter = (item: ChromeItem) => !normalized || item.label.toLocaleLowerCase().includes(normalized);
  const volumeItems = volumes.map<ChromeItem>((volume, index) => ({ kind: "volume", book: volume, label: fileStem(volume.file_name), number: index + 1 })).filter(filter);
  const chapterItems = chapters.map<ChromeItem>((chapter, index) => ({ kind: "chapter", chapter, label: chapter.label, number: index + 1 })).filter(filter);
  const items = descending ? [...volumeItems].reverse().concat([...chapterItems].reverse()) : volumeItems.concat(chapterItems);
  const pageCount = Math.max(1, Math.ceil(items.length / 20));
  const currentPage = Math.min(page, pageCount - 1);
  const shown = items.slice(currentPage * 20, currentPage * 20 + 20);
  return <div className="reader-chrome">
    {(volumes.length > 0 || chapters.length > 0) && <aside className="chapter-panel">
      <header><ListTree size={17} /><strong>章节与卷册</strong><button aria-label="收起" onClick={onClose}><X size={16} /></button></header>
      <div className="chapter-tools"><input type="search" aria-label="搜索章节或卷册" value={query} onChange={(event) => { setQuery(event.target.value); setPage(0); }} placeholder="搜索章节或卷册" /><button aria-label="切换排列顺序" onClick={() => { setDescending((value) => !value); setPage(0); }}>{descending ? "倒序" : "顺序"}</button></div>
      {shown.some((item) => item.kind === "volume") && <section className="chapter-list"><small>卷册</small>{shown.map((item) => item.kind === "volume" && <button key={item.book.id} className={item.book.id === book.id ? "active" : ""} onClick={() => onBook(item.book)}><span>{item.number}</span>{item.label}</button>)}</section>}
      {shown.some((item) => item.kind === "chapter") && <section className="chapter-list"><small>目录</small>{shown.map((item) => item.kind === "chapter" && <button key={`${item.chapter.href}-${item.number}`} style={{ "--level": item.chapter.level } as CSSProperties} onClick={() => controls?.goToChapter?.(item.chapter.href)}><span>{item.number}</span>{item.label}</button>)}</section>}
      {!shown.length && <p className="chapter-empty">没有匹配的章节或卷册</p>}
      <footer className="chapter-pagination"><button aria-label="上一页目录" disabled={currentPage === 0} onClick={() => setPage(currentPage - 1)}><ChevronLeft size={16} /></button><span>{currentPage + 1} / {pageCount}</span><button aria-label="下一页目录" disabled={currentPage + 1 === pageCount} onClick={() => setPage(currentPage + 1)}><ChevronRight size={16} /></button></footer>
    </aside>}
    <div className="floating-reader-bar">
      <button aria-label="上一页" onClick={controls?.previous}><ChevronLeft /></button>
      <div><span>{Math.round(progress * 100)}%</span><input aria-label="阅读进度" type="range" min={0} max={1} step={0.001} value={progress} disabled={!controls?.seek} onChange={(event) => controls?.seek?.(Number(event.target.value))} /></div>
      <button aria-label="下一页" onClick={controls?.next}><ChevronRight /></button>
    </div>
  </div>;
}

function ReflowReader({ book, settings, customFontURL, onCenter, onTurn, onControls, onProgress }: {
  book: Book;
  settings: ReadingSettings;
  customFontURL: string;
  onCenter: () => void;
  onTurn: () => void;
  onControls: (controls?: ReaderControls) => void;
  onProgress: (value: number) => void;
}) {
  const root = useRef<HTMLDivElement>(null);
  const view = useRef<FoliateViewElement | undefined>(undefined);
  const lastLocation = useRef<unknown>(undefined);
  const saveTimer = useRef(0);
  const [status, setStatus] = useState<"loading" | "ready" | "error">("loading");
  const [chapters, setChapters] = useState<Chapter[]>([]);

  useEffect(() => {
    let active = true;
    let initialized = false;
    let currentSection = 0;
    let preloadTimer = 0;
    let preloadRun = 0;
    let element: FoliateViewElement | undefined;
    let pendingProgress: { position: number; locator: string } | undefined;
    const preloaded = new Set<number>();
    const preload = async (current: number, run: number) => {
      const sections = element?.book?.sections;
      if (!book.is_comic || !sections) return;
      const end = comicWindowEnd(current, sections.length);
      for (const index of preloaded) {
        if (index >= current && index < end) continue;
        sections[index]?.unload?.();
        preloaded.delete(index);
      }
      for (let index = current + 1; index < end; index++) {
        if (!active || run !== preloadRun) return;
        if (preloaded.has(index)) continue;
        preloaded.add(index);
        try {
          await sections[index]?.load?.();
        } catch {
          preloaded.delete(index);
        }
      }
    };
    const schedulePreload = (current: number) => {
      currentSection = current;
      if (!initialized || !book.is_comic) return;
      const run = ++preloadRun;
      window.clearTimeout(preloadTimer);
      preloadTimer = window.setTimeout(() => void preload(current, run), 200);
    };
    const persist = (keepalive = false) => {
      if (!pendingProgress) return;
      const saved = pendingProgress;
      pendingProgress = undefined;
      saveProgress(book.id, saved.position, saved.locator, keepalive).catch(() => undefined);
    };
    void import("foliate-js/view.js").then(async ({ makeBook }) => {
      if (!active || !root.current) return;
      element = document.createElement("foliate-view") as FoliateViewElement;
      view.current = element;
      root.current.append(element);
      element.addEventListener("relocate", ((event: CustomEvent) => {
        const section = Number(event.detail?.section?.current ?? event.detail?.index);
        if (Number.isInteger(section)) schedulePreload(section);
        const position = Number(event.detail?.fraction);
        if (!Number.isFinite(position)) return;
        const bounded = Math.max(0, Math.min(1, position));
        const locator = typeof event.detail?.cfi === "string" ? event.detail.cfi : JSON.stringify({ fraction: bounded });
        lastLocation.current = locator;
        onProgress(bounded);
        pendingProgress = { position: bounded, locator };
        window.clearTimeout(saveTimer.current);
        saveTimer.current = window.setTimeout(() => persist(), 1200);
      }) as EventListener);
      const parsed = await makeBook(new RemoteFile(contentURL(book), `${book.title}.${book.format}`, book.size, bookMIME(book.format)));
      if (book.is_comic && settings.readingDirection !== "auto") parsed.dir = settings.readingDirection;
      await element.open(parsed);
      applySettings(element, settings, customFontURL);
      const savedLocation = lastLocation.current ?? (book.locator?.startsWith("epubcfi(") ? book.locator : book.progress > 0 ? { fraction: book.progress } : undefined);
      await element.init({ lastLocation: savedLocation, showTextStart: !savedLocation });
      if (active) {
        initialized = true;
        setChapters(flattenTOC(element.book?.toc ?? []));
        setStatus("ready");
        schedulePreload(currentSection);
      }
    }).catch(() => active && setStatus("error"));
    return () => {
      active = false;
      preloadRun++;
      window.clearTimeout(preloadTimer);
      window.clearTimeout(saveTimer.current);
      persist(true);
      for (const index of preloaded) element?.book?.sections?.[index]?.unload?.();
      preloaded.clear();
      element?.close();
      element?.remove();
      view.current = undefined;
    };
  }, [book, onProgress, settings.readingDirection]);

  useEffect(() => { if (view.current) applySettings(view.current, settings, customFontURL); }, [settings, customFontURL]);

  const move = useCallback(async (action: "previous" | "next") => {
    const element = view.current;
    if (!element) return;
    await (action === "previous" ? element.prev() : element.next());
    onTurn();
    if (settings.animation !== "none") element.animate(settings.animation === "slide"
      ? [{ opacity: .35, transform: "translateX(6%)" }, { opacity: 1, transform: "translateX(0)" }]
      : [{ opacity: .15 }, { opacity: 1 }], { duration: 240, easing: "cubic-bezier(.22,.8,.25,1)" });
  }, [onTurn, settings.animation]);

  useEffect(() => {
    if (status !== "ready") return;
    onControls({
      previous: () => void move("previous"),
      next: () => void move("next"),
      seek: (value) => void view.current?.goToFraction(value),
      chapters,
      goToChapter: (href) => void view.current?.goTo(href),
    });
    return () => onControls(undefined);
  }, [chapters, move, onControls, status]);

  return <div className="reflow-stage">
    <div ref={root} className="foliate-host" />
    {status === "loading" && <ReaderLoading />}
    {status === "error" && <div className="reader-message"><BookOpen size={40} /><h2>无法打开这本书</h2><p>文件可能加密、损坏，或不是受支持的 MOBI/KF8 格式。</p></div>}
    {status === "ready" && <PageZones visible={settings.showPageButtons} onLeft={() => void move("previous")} onCenter={onCenter} onRight={() => void move("next")} />}
  </div>;
}

function applySettings(view: FoliateViewElement, settings: ReadingSettings, customFontURL: string) {
  const renderer = view.renderer;
  if (!renderer) return;
  renderer.setAttribute("flow", "paginated");
  renderer.setAttribute("max-column-count", String(settings.columns));
  renderer.setAttribute("max-inline-size", settings.columns === 2 ? "620px" : "760px");
  renderer.setAttribute("gap", settings.columns === 2 ? "6%" : "8%");
  renderer.toggleAttribute("animated", settings.animation === "slide");
  renderer.setStyles?.(bookStyles(settings, customFontURL));
}

function bookStyles(settings: ReadingSettings, customFontURL: string) {
  const custom = settings.font === "custom" && customFontURL ? `@font-face { font-family: LumosCustom; src: url("${customFontURL}"); }` : "";
  const familyRule = settings.font === "book" ? "" : `font-family: ${fontFamily(settings.font)} !important;`;
  return `${custom}
    :root { --theme-bg-color: ${settings.backgroundColor}; }
    html, body { background: ${settings.backgroundColor} !important; color: ${settings.textColor} !important; ${familyRule}
      font-size: ${settings.fontSize}px !important; line-height: ${settings.lineHeight} !important; letter-spacing: ${settings.letterSpacing}em !important; }
    p { margin-block: 0 ${settings.paragraphSpacing}em !important; text-align: justify; }
    a { color: ${settings.textColor} !important; }`;
}

function TextReader({ book, settings, customFontURL, onCenter, onTurn, onControls, onProgress }: {
  book: Book;
  settings: ReadingSettings;
  customFontURL: string;
  onCenter: () => void;
  onTurn: () => void;
  onControls: (controls?: ReaderControls) => void;
  onProgress: (value: number) => void;
}) {
  const [text, setText] = useState("");
  const article = useRef<HTMLElement>(null);
  useEffect(() => {
    fetch(contentURL(book)).then((response) => response.text()).then((content) => {
      setText(content);
      requestAnimationFrame(() => {
        const element = article.current;
        if (element) element.scrollTop = book.progress * Math.max(0, element.scrollHeight - element.clientHeight);
      });
    });
  }, [book]);
  const save = useMemo(() => debounce(() => {
    const element = article.current;
    if (!element) return;
    const available = element.scrollHeight - element.clientHeight;
    const position = available > 0 ? element.scrollTop / available : 1;
    onProgress(position);
    saveProgress(book.id, position, String(element.scrollTop)).catch(() => undefined);
  }, 400), [book.id, onProgress]);
  useEffect(() => () => save.cancel(), [save]);
  useEffect(() => {
    const scroll = (pages: number) => {
      const element = article.current;
      if (!element) return;
      element.scrollBy({ top: pages * element.clientHeight * 0.88, behavior: settings.animation === "none" ? "auto" : "smooth" });
      onTurn();
    };
    onControls({
      previous: () => scroll(-1),
      next: () => scroll(1),
      seek: (value) => {
        const element = article.current;
        if (element) element.scrollTop = value * Math.max(0, element.scrollHeight - element.clientHeight);
      },
    });
    return () => onControls(undefined);
  }, [onControls, onTurn, settings.animation]);
  const style = {
    "--reader-bg": settings.backgroundColor,
    "--reader-fg": settings.textColor,
    "--reader-font": fontFamily(settings.font),
    "--reader-size": `${settings.fontSize}px`,
    "--reader-leading": settings.lineHeight,
    "--reader-tracking": `${settings.letterSpacing}em`,
    "--reader-paragraph": `${settings.paragraphSpacing}em`,
  } as CSSProperties;
  return <>
    {settings.font === "custom" && customFontURL && <style>{`@font-face { font-family: LumosCustom; src: url("${customFontURL}"); }`}</style>}
    <article ref={article} className="text-page" style={style} onScroll={save} onClick={onCenter}>{text ? text.split(/\n\s*\n/).map((paragraph, index) => <p key={index}>{paragraph}</p>) : "正在载入正文…"}</article>
  </>;
}

function ComicReader({ book, settings, direction, onCenter, onTurn, onControls, onProgress }: { book: Book; settings: ReadingSettings; direction: "ltr" | "rtl"; onCenter: () => void; onTurn: () => void; onControls: (controls?: ReaderControls) => void; onProgress: (value: number) => void }) {
  const [pages, setPages] = useState<ComicPage[]>([]);
  const [page, setPage] = useState(0);
  useEffect(() => {
    api<{ pages: ComicPage[] }>(`/api/books/${book.id}/pages`).then((result) => {
      setPages(result.pages);
      setPage(Math.max(0, Math.min(result.pages.length - 1, Math.round(book.progress * Math.max(0, result.pages.length - 1)))));
    });
  }, [book]);
  const turn = useCallback((delta: number) => {
    onTurn();
    setPage((current) => Math.max(0, Math.min(pages.length - 1, current + delta * settings.columns)));
  }, [onTurn, pages.length, settings.columns]);
  useEffect(() => {
    if (!pages.length) return;
    const position = pages.length === 1 ? 1 : page / (pages.length - 1);
    onProgress(position);
    const timer = window.setTimeout(() => saveProgress(book.id, position, String(page)), 250);
    return () => window.clearTimeout(timer);
  }, [book.id, onProgress, page, pages.length]);
  useEffect(() => {
    onControls({ previous: () => turn(-1), next: () => turn(1), seek: (value) => setPage(Math.round(value * Math.max(0, pages.length - 1))) });
    return () => onControls(undefined);
  }, [onControls, pages.length, turn]);
  if (!pages.length) return <ReaderLoading />;
  return <div className="comic-stage">
    <PageZones visible={settings.showPageButtons} onLeft={() => turn(-1)} onCenter={onCenter} onRight={() => turn(1)} />
    <div className={`comic-pages ${direction}`}>
      {pages.slice(page, comicWindowEnd(page, pages.length, settings.columns)).map((item, index) => {
        const visible = index < settings.columns;
        return <img key={item.index} className={visible ? `turn-${settings.animation}` : "comic-preload"} src={item.url} alt={visible ? `第 ${page + index + 1} 页` : ""} aria-hidden={!visible} loading="eager" decoding="async" fetchPriority={visible ? "high" : "low"} />;
      })}
    </div>
  </div>;
}

function PDFReader({ book, settings, onCenter, onTurn, onControls, onProgress }: { book: Book; settings: ReadingSettings; onCenter: () => void; onTurn: () => void; onControls: (controls?: ReaderControls) => void; onProgress: (value: number) => void }) {
  const stage = useRef<HTMLDivElement>(null);
  const canvas = useRef<HTMLCanvasElement>(null);
  const documentRef = useRef<{ getPage: (page: number) => Promise<any> } | undefined>(undefined);
  const loadingTask = useRef<{ destroy: () => Promise<void> } | undefined>(undefined);
  const renderTask = useRef<{ cancel: () => void } | undefined>(undefined);
  const [count, setCount] = useState(0);
  const [page, setPage] = useState(0);
  const turn = useCallback((delta: number) => {
    onTurn();
    setPage((current) => Math.max(0, Math.min(count - 1, current + delta)));
  }, [count, onTurn]);

  useEffect(() => {
    let active = true;
    void import("pdfjs-dist").then(async (pdfjs) => {
      pdfjs.GlobalWorkerOptions.workerSrc = pdfWorker;
      const task = pdfjs.getDocument({ url: contentURL(book), withCredentials: true, disableAutoFetch: true, disableStream: true, rangeChunkSize: 64 * 1024 });
      loadingTask.current = task;
      const pdf = await task.promise;
      if (!active) return void task.destroy();
      documentRef.current = pdf;
      setCount(pdf.numPages);
      setPage(Math.max(0, Math.min(pdf.numPages - 1, Math.round(book.progress * Math.max(0, pdf.numPages - 1)))));
    }).catch(() => undefined);
    return () => {
      active = false;
      renderTask.current?.cancel();
      void loadingTask.current?.destroy();
      loadingTask.current = undefined;
      documentRef.current = undefined;
    };
  }, [book]);

  useEffect(() => {
    const pdf = documentRef.current;
    const target = canvas.current;
    const root = stage.current;
    if (!pdf || !target || !root || !count) return;
    let active = true;
    void pdf.getPage(page + 1).then((pdfPage) => {
      if (!active) return;
      const initial = pdfPage.getViewport({ scale: 1 });
      const fit = Math.min(root.clientWidth / initial.width, root.clientHeight / initial.height);
      const scale = fit * Math.min(2, window.devicePixelRatio || 1);
      const viewport = pdfPage.getViewport({ scale });
      target.width = Math.ceil(viewport.width);
      target.height = Math.ceil(viewport.height);
      target.style.width = `${viewport.width / Math.min(2, window.devicePixelRatio || 1)}px`;
      target.style.height = `${viewport.height / Math.min(2, window.devicePixelRatio || 1)}px`;
      const task = pdfPage.render({ canvas: target, canvasContext: target.getContext("2d")!, viewport });
      renderTask.current = task;
      return task.promise.finally(() => pdfPage.cleanup());
    }).catch(() => undefined);
    const position = count === 1 ? 1 : page / (count - 1);
    onProgress(position);
    const timer = window.setTimeout(() => saveProgress(book.id, position, String(page)), 250);
    return () => {
      active = false;
      window.clearTimeout(timer);
      renderTask.current?.cancel();
    };
  }, [book.id, count, onProgress, page]);

  useEffect(() => {
    onControls({ previous: () => turn(-1), next: () => turn(1), seek: (value) => setPage(Math.round(value * Math.max(0, count - 1))) });
    return () => onControls(undefined);
  }, [count, onControls, turn]);
  if (!count) return <ReaderLoading />;
  return <div ref={stage} className="pdf-stage">
    <PageZones visible={settings.showPageButtons} onLeft={() => turn(-1)} onCenter={onCenter} onRight={() => turn(1)} />
    <canvas key={page} ref={canvas} className={`turn-${settings.animation}`} />
  </div>;
}

function PageZones({ visible, onLeft, onCenter, onRight }: { visible: boolean; onLeft: () => void; onCenter: () => void; onRight: () => void }) {
  return <>
    <button className={`tap-zone left ${visible ? "page-button-visible" : ""}`} aria-label="上一页" onClick={onLeft}>{visible && <span><ChevronLeft /></span>}</button>
    <button className="tap-zone center" aria-label="显示阅读菜单" onClick={onCenter} />
    <button className={`tap-zone right ${visible ? "page-button-visible" : ""}`} aria-label="下一页" onClick={onRight}>{visible && <span><ChevronRight /></span>}</button>
  </>;
}

function SettingsPanel({ settings, templates, styleable, comic, onChange, onClose, onFont, onSave, onDelete }: {
  settings: ReadingSettings;
  templates: ReadingTemplate[];
  styleable: boolean;
  comic: boolean;
  onChange: (value: ReadingSettings | ((current: ReadingSettings) => ReadingSettings)) => void;
  onClose: () => void;
  onFont: (name: string) => void;
  onSave: (name: string) => void;
  onDelete: (id: string) => void;
}) {
  const [templateName, setTemplateName] = useState("");
  const [fonts, setFonts] = useState<ServerFont[]>([]);
  const [fontBusy, setFontBusy] = useState(false);
  const [fontError, setFontError] = useState("");
  const patch = (value: Partial<ReadingSettings>) => onChange((current) => ({ ...current, ...value }));
  const loadFonts = useCallback(() => api<{ fonts: ServerFont[] }>("/api/fonts").then((result) => setFonts(result.fonts)), []);
  useEffect(() => {
    loadFonts().catch((reason) => setFontError(reason.message));
    const timer = window.setInterval(() => loadFonts().catch(() => undefined), 5000);
    return () => window.clearInterval(timer);
  }, [loadFonts]);
  const upload = async (event: FormEvent<HTMLInputElement>) => {
    const file = event.currentTarget.files?.[0];
    if (!file) return;
    setFontBusy(true);
    setFontError("");
    const form = new FormData();
    form.append("font", file);
    try {
      const result = await api<{ font: ServerFont }>("/api/fonts", { method: "POST", body: form });
      onFont(result.font.name);
      await loadFonts();
    } catch (reason) {
      setFontError(reason instanceof Error ? reason.message : "字体上传失败");
    } finally {
      setFontBusy(false);
    }
  };
  return <aside className="settings-panel" aria-label="阅读设置">
    <header><div><small>阅读设置</small><strong>排版与翻页</strong></div><button aria-label="关闭设置" onClick={onClose}><X size={18} /></button></header>
    <section><label>排版模板</label><div className="template-grid">{templates.map((template) => <div key={template.id} className="template-item">
      <button className={settings.template === template.id ? "active" : ""} onClick={() => onChange({ ...template.settings, template: template.id })}><span className="template-swatch" style={{ backgroundColor: template.settings.backgroundColor, color: template.settings.textColor }}>Aa</span><strong>{template.name}</strong><small>{template.hint}</small></button>
      {template.custom && <button className="delete-template" aria-label={`删除${template.name}`} onClick={() => onDelete(template.id)}><Trash2 size={12} /></button>}
    </div>)}</div>
      <form className="save-template" onSubmit={(event) => { event.preventDefault(); const name = templateName.trim(); if (name) { onSave(name); setTemplateName(""); } }}><input value={templateName} maxLength={24} onChange={(event) => setTemplateName(event.target.value)} placeholder="新模板名称" /><button disabled={!templateName.trim()}><Save size={14} />保存当前参数</button></form>
    </section>
    <section className="setting-fields">
      <label>字体<select value={settings.font} onChange={(event) => patch({ font: event.target.value as ReadingSettings["font"] })}><option value="book">书籍原字体</option><option value="serif">宋体 / 衬线</option><option value="sans">黑体 / 无衬线</option><option value="system">系统字体</option><option value="custom" disabled={!settings.fontFile}>服务端字体</option></select></label>
      <label>服务端字体（正文生效）<select aria-label="服务端字体" value={settings.fontFile} onChange={(event) => patch({ fontFile: event.target.value, font: event.target.value ? "custom" : "serif" })}><option value="">选择字体</option>{fonts.map((font) => <option key={font.name} value={font.name}>{font.name}</option>)}</select></label>
      <label className="font-upload">{fontBusy ? "正在上传…" : "上传字体到服务端"}<input disabled={fontBusy} type="file" accept=".ttf,.otf,.woff,.woff2" onInput={upload} /></label>
      {fontError && <p className="font-error">{fontError}</p>}
      {styleable && <>
        <Range label="字号" value={settings.fontSize} min={14} max={32} step={1} suffix="px" onChange={(fontSize) => patch({ fontSize })} />
        <Range label="行间距" value={settings.lineHeight} min={1.2} max={2.4} step={0.05} onChange={(lineHeight) => patch({ lineHeight })} />
        <Range label="段间距" value={settings.paragraphSpacing} min={0} max={2} step={0.1} suffix="em" onChange={(paragraphSpacing) => patch({ paragraphSpacing })} />
        <Range label="字间距" value={settings.letterSpacing} min={-0.05} max={0.3} step={0.01} suffix="em" onChange={(letterSpacing) => patch({ letterSpacing })} />
      </>}
      <label className="color-field">页面底色<input aria-label="页面底色" type="color" value={settings.backgroundColor} onChange={(event) => patch({ backgroundColor: event.target.value })} /></label>
      <label className="color-field">文字颜色<input aria-label="文字颜色" type="color" value={settings.textColor} onChange={(event) => patch({ textColor: event.target.value })} /></label>
      {(styleable || comic) && <label>页面模式<select value={settings.columns} onChange={(event) => patch({ columns: Number(event.target.value) as 1 | 2 })}><option value={1}>单页</option><option value={2}>双页（横屏）</option></select></label>}
      <label>翻页动画<select value={settings.animation} onChange={(event) => patch({ animation: event.target.value as ReadingSettings["animation"] })}><option value="none">无动画</option><option value="slide">平滑滑动</option><option value="fade">淡入淡出</option></select></label>
      <label className="toggle-field"><span>显示半透明翻页键</span><input type="checkbox" checked={settings.showPageButtons} onChange={(event) => patch({ showPageButtons: event.target.checked })} /></label>
      {comic && <label>漫画阅读方向<select value={settings.readingDirection} onChange={(event) => patch({ readingDirection: event.target.value as ReadingSettings["readingDirection"] })}><option value="auto">自动识别</option><option value="rtl">日漫 · 从右向左</option><option value="ltr">普通 · 从左向右</option></select></label>}
    </section>
  </aside>;
}

function Range({ label, value, min, max, step, suffix = "", onChange }: { label: string; value: number; min: number; max: number; step: number; suffix?: string; onChange: (value: number) => void }) {
  return <label className="range-field"><span>{label}<output>{value}{suffix}</output></span><input type="range" value={value} min={min} max={max} step={step} onChange={(event) => onChange(Number(event.target.value))} /></label>;
}

function ReaderLoading() {
  return <div className="reader-loading"><LoaderCircle className="spin" /><span>正在排版…</span></div>;
}

function loadSettings(): ReadingSettings {
  try {
    return migrateSettings(JSON.parse(localStorage.getItem("lumos-reading-settings") ?? "{}"));
  } catch {
    return defaultSettings;
  }
}

function loadTemplates(): ReadingTemplate[] {
  try {
    const value = JSON.parse(localStorage.getItem("lumos-reading-templates") ?? "[]");
    return Array.isArray(value) ? value.slice(0, 12).map((template) => ({ ...template, settings: withoutTemplate(migrateSettings({ ...template.settings, template: template.id })) })) : [];
  } catch {
    return [];
  }
}

function withoutTemplate(settings: ReadingSettings): Omit<ReadingSettings, "template"> {
  const { template: _template, ...values } = settings;
  return values;
}

function migrateSettings(saved: Partial<ReadingSettings> & { theme?: LegacyTheme }) {
  const oldTheme = saved.theme ?? (["paper", "clean", "eink", "night"].includes(saved.template ?? "") ? saved.template as LegacyTheme : "paper");
  const colors = legacyThemeColors(oldTheme);
  const { theme: _theme, ...current } = saved;
  return {
    ...defaultSettings,
    ...current,
    backgroundColor: saved.backgroundColor ?? colors.background,
    textColor: saved.textColor ?? colors.foreground,
    font: saved.font === "custom" && !saved.fontFile ? "serif" : saved.font ?? defaultSettings.font,
  };
}

function legacyThemeColors(theme: LegacyTheme) {
  if (theme === "night") return { background: "#161a18", foreground: "#d9ddd9" };
  if (theme === "eink") return { background: "#ffffff", foreground: "#000000" };
  if (theme === "clean") return { background: "#ffffff", foreground: "#202522" };
  return { background: "#f7f1e4", foreground: "#29261f" };
}

function fontFamily(font: ReadingSettings["font"]) {
  if (font === "sans") return '"Noto Sans CJK SC", "Microsoft YaHei", sans-serif';
  if (font === "system") return '-apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif';
  if (font === "custom") return "LumosCustom, serif";
  return '"Noto Serif CJK SC", "Songti SC", "STSong", serif';
}

function bookMIME(format: Book["format"]) {
  if (format === "epub") return "application/epub+zip";
  if (format === "mobi") return "application/x-mobipocket-ebook";
  return "application/vnd.amazon.ebook";
}

function flattenTOC(items: FoliateTOCItem[], level = 0): Chapter[] {
  const chapters: Chapter[] = [];
  for (const item of items) {
    if (item.href && item.label) chapters.push({ label: item.label, href: item.href, level });
    chapters.push(...flattenTOC(item.subitems ?? [], level + 1));
  }
  return chapters;
}

function useReadingTimer(bookID: string) {
  useEffect(() => {
    let last = Date.now();
    let queued = 0;
    let visible = document.visibilityState === "visible";
    const flush = (force = false) => {
      const now = Date.now();
      if (visible) queued += Math.min(60, (now - last) / 1000);
      last = now;
      visible = document.visibilityState === "visible";
      if (queued < (force ? 1 : 20)) return;
      const seconds = Math.min(300, Math.floor(queued));
      queued -= seconds;
      addReadingTime(bookID, seconds).catch(() => undefined);
    };
    const timer = window.setInterval(() => flush(), 30000);
    const visibility = () => flush(true);
    document.addEventListener("visibilitychange", visibility);
    return () => {
      window.clearInterval(timer);
      document.removeEventListener("visibilitychange", visibility);
      flush(true);
    };
  }, [bookID]);
}

function debounce(callback: () => void, delay: number) {
  let timer = 0;
  const fn = () => { window.clearTimeout(timer); timer = window.setTimeout(callback, delay); };
  fn.cancel = () => window.clearTimeout(timer);
  return fn;
}
