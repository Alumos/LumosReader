import { BookOpen } from "lucide-react";
import { useCallback, useEffect, useRef, useState } from "react";
import { Book, RemoteFile, contentURL, saveProgress } from "../model";
import { fontFamily } from "./settings";
import { PageZones, ReaderLoading } from "./ReaderCommon";
import { Chapter, ReaderAdapterProps, ReadingSettings } from "./types";

const fixedLayoutPreload = 3;

export function FoliateReader({ book, settings, customFontURL, onCenter, onControls, onProgress }: ReaderAdapterProps & {
  book: Book;
  settings: ReadingSettings;
  customFontURL: string;
}) {
  const root = useRef<HTMLDivElement>(null);
  const view = useRef<FoliateViewElement | undefined>(undefined);
  const lastLocation = useRef<unknown>(undefined);
  const saveTimer = useRef(0);
  const [status, setStatus] = useState<"loading" | "ready" | "error">("loading");
  const [chapters, setChapters] = useState<Chapter[]>([]);
  const [location, setLocation] = useState("");
  const [currentChapter, setCurrentChapter] = useState("");
  const [retry, setRetry] = useState(0);
  const [failure, setFailure] = useState("");

  useEffect(() => {
    setStatus("loading");
    setFailure("");
    setChapters([]);
    setLocation("");
    setCurrentChapter("");
    let active = true;
    let initialized = false;
    let currentSection = 0;
    let preloadTimer = 0;
    let preloadRun = 0;
    let initializationTimer = 0;
    let stage = "载入排版脚本";
    let element: FoliateViewElement | undefined;
    let pendingProgress: { position: number; locator: string } | undefined;
    const preloaded = new Set<number>();

    const preload = async (current: number, run: number) => {
      const sections = element?.book?.sections;
      if (!book.fixed_layout || !sections) return;
      const end = Math.min(sections.length, current + fixedLayoutPreload + 1);
      for (const index of preloaded) {
        if (index >= current && index < end) continue;
        sections[index]?.unload?.();
        preloaded.delete(index);
      }
      for (let index = current + 1; index < end; index++) {
        if (!active || run !== preloadRun) return;
        if (preloaded.has(index)) continue;
        preloaded.add(index);
        try { await sections[index]?.load?.(); } catch { preloaded.delete(index); }
      }
    };
    const schedulePreload = (current: number) => {
      currentSection = current;
      if (!initialized || !book.fixed_layout) return;
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
    const initialize = async () => {
      const { makeBook } = await import("foliate-js/view.js");
      if (!active || !root.current) return;
      element = document.createElement("foliate-view") as FoliateViewElement;
      view.current = element;
      root.current.replaceChildren(element);
      element.addEventListener("relocate", ((event: CustomEvent) => {
        const section = Number(event.detail?.section?.current ?? event.detail?.index);
        if (Number.isInteger(section)) schedulePreload(section);
        const chapter = event.detail?.tocItem;
        const page = event.detail?.location;
        const pageLabel = Number.isInteger(page?.current) && Number.isInteger(page?.total) ? `第 ${page.current + 1} / ${page.total} 页` : "";
        setLocation([chapter?.label, pageLabel].filter(Boolean).join(" · "));
        setCurrentChapter(typeof chapter?.href === "string" ? chapter.href : "");
        const position = Number(event.detail?.fraction);
        if (!Number.isFinite(position)) return;
        const bounded = Math.max(0, Math.min(1, position));
        const locator = typeof event.detail?.cfi === "string" ? event.detail.cfi : JSON.stringify({ fraction: bounded });
        lastLocation.current = locator;
        onProgress(bounded);
        pendingProgress = { position: bounded, locator };
        window.clearTimeout(saveTimer.current);
        saveTimer.current = window.setTimeout(persist, 1200);
      }) as EventListener);
      stage = "解析书籍";
      const parsed = await makeBook(new RemoteFile(contentURL(book), `${book.title}.${book.format}`, book.size, bookMIME(book.format)));
      if (book.fixed_layout && settings.readingDirection !== "auto") parsed.dir = settings.readingDirection;
      stage = "创建阅读页面";
      await element.open(parsed);
      applySettings(element, settings, customFontURL, !book.fixed_layout);
      const savedLocation = lastLocation.current ?? (book.locator?.startsWith("epubcfi(") ? book.locator : book.progress > 0 ? { fraction: book.progress } : undefined);
      stage = "分页正文";
      await element.init({ lastLocation: savedLocation, showTextStart: !savedLocation });
      if (active) {
        initialized = true;
        setChapters(flattenTOC(element.book?.toc ?? []));
        setStatus("ready");
        schedulePreload(currentSection);
      }
    };
    const timeout = new Promise<never>((_, reject) => {
      initializationTimer = window.setTimeout(() => reject(new Error(`${stage}超过 20 秒`)), 20000);
    });
    void Promise.race([initialize(), timeout]).catch((error) => {
      if (!active) return;
      console.error("Reader initialization failed", error);
      active = false;
      setFailure(error instanceof Error ? error.message : String(error));
      setStatus("error");
    }).finally(() => window.clearTimeout(initializationTimer));
    return () => {
      active = false;
      preloadRun++;
      window.clearTimeout(initializationTimer);
      window.clearTimeout(preloadTimer);
      window.clearTimeout(saveTimer.current);
      persist(true);
      for (const index of preloaded) element?.book?.sections?.[index]?.unload?.();
      element?.close();
      element?.remove();
      view.current = undefined;
    };
  }, [book, onProgress, retry, settings.readingDirection]);

  useEffect(() => {
    if (view.current) applySettings(view.current, settings, customFontURL, !book.fixed_layout);
  }, [book.fixed_layout, customFontURL, settings]);

  const move = useCallback(async (action: "previous" | "next") => {
    const element = view.current;
    if (!element) return;
    await (action === "previous" ? element.prev() : element.next());
    // Reflowable pagination owns its scroll animation. Fixed-layout replaces
    // iframe spreads, so animating the host as well can expose compositor
    // frames on Android WebView.
    if (!book.fixed_layout && settings.animation !== "none") element.animate(settings.animation === "slide"
      ? [{ opacity: .35, transform: "translateX(6%)" }, { opacity: 1, transform: "translateX(0)" }]
      : [{ opacity: .15 }, { opacity: 1 }], { duration: 240, easing: "cubic-bezier(.22,.8,.25,1)" });
  }, [book.fixed_layout, settings.animation]);

  useEffect(() => {
    if (status !== "ready") return;
    onControls({
      previous: () => void move("previous"),
      next: () => void move("next"),
      seek: (value) => void view.current?.goToFraction(value),
      chapters,
      location,
      currentChapter,
      goToChapter: (href) => void view.current?.goTo(href),
    });
    return () => onControls(undefined);
  }, [chapters, currentChapter, location, move, onControls, status]);

  return <div className="reflow-stage">
    <div ref={root} className="foliate-host" />
    {status === "loading" && <ReaderLoading />}
    {status === "error" && <div className="reader-message"><BookOpen size={40} /><h2>排版失败</h2><p>{failure || "无法打开这本书"}</p><button onClick={() => setRetry((value) => value + 1)}>重新排版</button></div>}
    {status === "ready" && <PageZones visible={settings.showPageButtons} onLeft={() => void move("previous")} onCenter={onCenter} onRight={() => void move("next")} />}
  </div>;
}

function applySettings(view: FoliateViewElement, settings: ReadingSettings, customFontURL: string, styleable: boolean) {
  const renderer = view.renderer;
  if (!renderer) return;
  renderer.setAttribute("flow", "paginated");
  renderer.setAttribute("max-column-count", String(settings.columns));
  renderer.setAttribute("max-inline-size", settings.columns === 2 ? "620px" : "760px");
  renderer.setAttribute("gap", settings.columns === 2 ? "6%" : "8%");
  renderer.toggleAttribute("animated", settings.animation === "slide");
  if (styleable) renderer.setStyles?.(bookStyles(settings, customFontURL));
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
