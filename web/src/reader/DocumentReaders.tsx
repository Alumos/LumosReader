import pdfWorker from "pdfjs-dist/build/pdf.worker.min.mjs?url";
import { CSSProperties, useCallback, useEffect, useMemo, useRef, useState } from "react";
import { Book, ComicPage, api, contentURL, saveProgress } from "../model";
import { fontFamily } from "./settings";
import { PageZones, ReaderFailure, ReaderLoading } from "./ReaderCommon";
import { ReaderAdapterProps, ReadingSettings } from "./types";

const comicPreloadPages = 4;

export function TextReader({ book, settings, customFontURL, onCenter, onControls, onProgress }: ReaderAdapterProps & { book: Book; settings: ReadingSettings; customFontURL: string }) {
  const [text, setText] = useState<string>();
  const [failure, setFailure] = useState("");
  const article = useRef<HTMLElement>(null);
  useEffect(() => {
    const controller = new AbortController();
    setText(undefined);
    setFailure("");
    fetch(contentURL(book), { signal: controller.signal }).then((response) => {
      if (!response.ok) throw new Error(`读取正文失败（HTTP ${response.status}）`);
      return response.text();
    }).then((content) => {
      setText(content);
      requestAnimationFrame(() => {
        const element = article.current;
        if (element) element.scrollTop = book.progress * Math.max(0, element.scrollHeight - element.clientHeight);
      });
    }).catch((error) => {
      if (error.name === "AbortError") return;
      console.error("Text reader failed", error);
      setFailure(error instanceof Error ? error.message : String(error));
    });
    return () => controller.abort();
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
    const scroll = (pages: number) => article.current?.scrollBy({ top: pages * article.current.clientHeight * .88, behavior: settings.animation === "none" ? "auto" : "smooth" });
    onControls({
      previous: () => scroll(-1),
      next: () => scroll(1),
      seek: (value) => { if (article.current) article.current.scrollTop = value * Math.max(0, article.current.scrollHeight - article.current.clientHeight); },
    });
    return () => onControls(undefined);
  }, [onControls, settings.animation]);
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
    {failure ? <ReaderFailure message={failure} /> : text === undefined ? <ReaderLoading label="正在载入正文…" /> : <article ref={article} className="text-page" style={style} onScroll={save} onClick={onCenter}>{text ? text.split(/\n\s*\n/).map((paragraph, index) => <p key={index}>{paragraph}</p>) : <p>正文为空</p>}</article>}
  </>;
}

export function ComicReader({ book, settings, direction, onCenter, onControls, onProgress }: ReaderAdapterProps & { book: Book; settings: ReadingSettings; direction: "ltr" | "rtl" }) {
  const [pages, setPages] = useState<ComicPage[]>([]);
  const [page, setPage] = useState(0);
  const [failure, setFailure] = useState("");
  useEffect(() => {
    let active = true;
    setPages([]);
    setFailure("");
    api<{ pages: ComicPage[] }>(`/api/books/${book.id}/pages`).then((result) => {
      if (!active) return;
      if (!result.pages.length) throw new Error("压缩包中没有可读取的图片");
      setPages(result.pages);
      setPage(Math.max(0, Math.min(result.pages.length - 1, Math.round(book.progress * Math.max(0, result.pages.length - 1)))));
    }).catch((error) => {
      if (!active) return;
      console.error("Comic reader failed", error);
      setFailure(error instanceof Error ? error.message : String(error));
    });
    return () => { active = false; };
  }, [book]);
  const turn = useCallback((delta: number) => setPage((current) => Math.max(0, Math.min(pages.length - 1, current + delta * settings.columns))), [pages.length, settings.columns]);
  useEffect(() => {
    if (!pages.length) return;
    const position = pages.length === 1 ? 1 : page / (pages.length - 1);
    onProgress(position);
    const timer = window.setTimeout(() => saveProgress(book.id, position, String(page)), 250);
    return () => window.clearTimeout(timer);
  }, [book.id, onProgress, page, pages.length]);
  useEffect(() => {
    onControls({ previous: () => turn(-1), next: () => turn(1), seek: (value) => setPage(Math.round(value * Math.max(0, pages.length - 1))), location: pages.length ? `第 ${page + 1} / ${pages.length} 页` : undefined });
    return () => onControls(undefined);
  }, [onControls, page, pages.length, turn]);
  if (failure) return <ReaderFailure message={failure} />;
  if (!pages.length) return <ReaderLoading label="正在载入漫画…" />;
  const end = Math.min(pages.length, page + settings.columns + comicPreloadPages);
  return <div className="comic-stage">
    <PageZones visible={settings.showPageButtons} onLeft={() => turn(-1)} onCenter={onCenter} onRight={() => turn(1)} />
    <div className={`comic-pages ${direction}`}>
      {pages.slice(page, end).map((item, index) => {
        const visible = index < settings.columns;
        return <img key={item.index} className={visible ? `turn-${settings.animation}` : "comic-preload"} src={item.url} alt={visible ? `第 ${page + index + 1} 页` : ""} aria-hidden={!visible} loading={visible ? "eager" : "lazy"} decoding="async" fetchPriority={visible ? "high" : "low"} />;
      })}
    </div>
  </div>;
}

export function PDFReader({ book, settings, onCenter, onControls, onProgress }: ReaderAdapterProps & { book: Book; settings: ReadingSettings }) {
  const stage = useRef<HTMLDivElement>(null);
  const canvas = useRef<HTMLCanvasElement>(null);
  const documentRef = useRef<{ getPage: (page: number) => Promise<any> } | undefined>(undefined);
  const loadingTask = useRef<{ destroy: () => Promise<void> } | undefined>(undefined);
  const renderTask = useRef<{ cancel: () => void } | undefined>(undefined);
  const [count, setCount] = useState(0);
  const [page, setPage] = useState(0);
  const [failure, setFailure] = useState("");
  const turn = useCallback((delta: number) => setPage((current) => Math.max(0, Math.min(count - 1, current + delta))), [count]);

  useEffect(() => {
    let active = true;
    setCount(0);
    setFailure("");
    void import("pdfjs-dist").then(async (pdfjs) => {
      pdfjs.GlobalWorkerOptions.workerSrc = pdfWorker;
      const task = pdfjs.getDocument({ url: contentURL(book), withCredentials: true, disableAutoFetch: true, disableStream: true, rangeChunkSize: 64 * 1024 });
      loadingTask.current = task;
      const pdf = await task.promise;
      if (!active) return void task.destroy();
      documentRef.current = pdf;
      setCount(pdf.numPages);
      setPage(Math.max(0, Math.min(pdf.numPages - 1, Math.round(book.progress * Math.max(0, pdf.numPages - 1)))));
    }).catch((error) => {
      if (!active) return;
      console.error("PDF reader failed", error);
      setFailure(error instanceof Error ? error.message : String(error));
    });
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
      const pixelRatio = Math.min(2, window.devicePixelRatio || 1);
      const viewport = pdfPage.getViewport({ scale: Math.min(root.clientWidth / initial.width, root.clientHeight / initial.height) * pixelRatio });
      target.width = Math.ceil(viewport.width);
      target.height = Math.ceil(viewport.height);
      target.style.width = `${viewport.width / pixelRatio}px`;
      target.style.height = `${viewport.height / pixelRatio}px`;
      const task = pdfPage.render({ canvas: target, canvasContext: target.getContext("2d")!, viewport });
      renderTask.current = task;
      return task.promise.finally(() => pdfPage.cleanup());
    }).catch((error) => {
      if (error?.name === "RenderingCancelledException" || !active) return;
      console.error("PDF page failed", error);
      setFailure(error instanceof Error ? error.message : String(error));
    });
    const position = count === 1 ? 1 : page / (count - 1);
    onProgress(position);
    const timer = window.setTimeout(() => saveProgress(book.id, position, String(page)), 250);
    return () => { active = false; window.clearTimeout(timer); renderTask.current?.cancel(); };
  }, [book.id, count, onProgress, page]);

  useEffect(() => {
    onControls({ previous: () => turn(-1), next: () => turn(1), seek: (value) => setPage(Math.round(value * Math.max(0, count - 1))), location: count ? `第 ${page + 1} / ${count} 页` : undefined });
    return () => onControls(undefined);
  }, [count, onControls, page, turn]);
  if (failure) return <ReaderFailure message={failure} />;
  if (!count) return <ReaderLoading label="正在载入 PDF…" />;
  return <div ref={stage} className="pdf-stage">
    <PageZones visible={settings.showPageButtons} onLeft={() => turn(-1)} onCenter={onCenter} onRight={() => turn(1)} />
    <canvas key={page} ref={canvas} className={`turn-${settings.animation}`} />
  </div>;
}

function debounce(callback: () => void, delay: number) {
  let timer = 0;
  const fn = () => { window.clearTimeout(timer); timer = window.setTimeout(callback, delay); };
  fn.cancel = () => window.clearTimeout(timer);
  return fn;
}
