import { RefObject, useEffect, useRef, useState } from "react";
import pdfWorker from "pdfjs-dist/build/pdf.worker.min.mjs?url";
import { Book, contentURL, RemoteFile } from "./model";

export function BookCover({ book }: { book: Book }) {
  if (book.cover_url) return <ImageCover src={book.cover_url} title={book.title} />;
  if (book.format === "pdf") return <PDFCover book={book} />;
  if (book.format === "mobi" || book.format === "azw3") return <KindleCover book={book} />;
  return null;
}

function ImageCover({ src, title }: { src: string; title: string }) {
  const [failed, setFailed] = useState(false);
  return failed ? null : <img className="recognized-cover" src={src} alt={`${title}封面`} loading="lazy" onError={() => setFailed(true)} />;
}

function KindleCover({ book }: { book: Book }) {
  const root = useRef<HTMLDivElement>(null);
  const visible = useVisible(root);
  const [src, setSrc] = useState("");
  useEffect(() => {
    if (!visible) return;
    let active = true;
    let objectURL = "";
    import("foliate-js/view.js")
      .then(({ makeBook }) => makeBook(new RemoteFile(contentURL(book), `${book.title}.${book.format}`, book.size, mimeType(book.format))))
      .then((parsed) => parsed.getCover?.())
      .then((cover) => {
        if (!active || !cover) return;
        objectURL = URL.createObjectURL(cover);
        setSrc(objectURL);
      })
      .catch(() => undefined);
    return () => {
      active = false;
      if (objectURL) URL.revokeObjectURL(objectURL);
    };
  }, [book, visible]);
  return <div ref={root} className="cover-render-target">{src && <img className="recognized-cover" src={src} alt={`${book.title}封面`} />}</div>;
}

function PDFCover({ book }: { book: Book }) {
  const canvas = useRef<HTMLCanvasElement>(null);
  const visible = useVisible(canvas);
  useEffect(() => {
    if (!visible || !canvas.current) return;
    let active = true;
    let task: { destroy: () => Promise<void> } | undefined;
    void import("pdfjs-dist").then(async (pdfjs) => {
      pdfjs.GlobalWorkerOptions.workerSrc = pdfWorker;
      const loading = pdfjs.getDocument({
        url: contentURL(book),
        withCredentials: true,
        disableAutoFetch: true,
        disableStream: true,
        rangeChunkSize: 64 * 1024,
      });
      task = loading;
      const pdf = await loading.promise;
      const page = await pdf.getPage(1);
      const initial = page.getViewport({ scale: 1 });
      const viewport = page.getViewport({ scale: 360 / initial.width });
      const target = canvas.current;
      if (!active || !target) return;
      target.width = Math.ceil(viewport.width);
      target.height = Math.ceil(viewport.height);
      await page.render({ canvas: target, canvasContext: target.getContext("2d")!, viewport }).promise;
    }).catch(() => undefined);
    return () => {
      active = false;
      void task?.destroy();
    };
  }, [book, visible]);
  return <canvas ref={canvas} className="recognized-cover" aria-label={`${book.title}封面`} />;
}

function useVisible(target: RefObject<Element | null>) {
  const [visible, setVisible] = useState(false);
  useEffect(() => {
    const element = target.current;
    if (!element) return;
    const observer = new IntersectionObserver(([entry]) => {
      if (entry.isIntersecting) {
        setVisible(true);
        observer.disconnect();
      }
    }, { rootMargin: "160px" });
    observer.observe(element);
    return () => observer.disconnect();
  }, [target]);
  return visible;
}

function mimeType(format: Book["format"]) {
  if (format === "mobi") return "application/x-mobipocket-ebook";
  if (format === "azw3") return "application/vnd.amazon.ebook";
  return "application/octet-stream";
}
