import { Check, ChevronLeft, ChevronRight, Download, Upload } from "lucide-react";
import { CSSProperties, FormEvent, useCallback, useEffect, useMemo, useState } from "react";
import { ServerFont, api } from "./model";

export type FontChoice = { font: "book" | "system" | "serif" | "sans" | "custom"; fontFile: string };

const databaseName = "lumos-fonts-v1";
const fontStore = "fonts";
const pageSize = 6;
const builtInFonts: { id: FontChoice["font"]; name: string; detail: string }[] = [
  { id: "book", name: "书籍原字体", detail: "优先保留书籍内嵌字体" },
  { id: "system", name: "系统字体", detail: "跟随当前设备" },
  { id: "serif", name: "宋体 / 衬线", detail: "适合长篇阅读" },
  { id: "sans", name: "黑体 / 无衬线", detail: "清晰简洁" },
];

const fontURL = (name: string) => `/api/fonts/${encodeURIComponent(name)}`;

const openFonts = () => new Promise<IDBDatabase>((resolve, reject) => {
  const request = indexedDB.open(databaseName, 1);
  request.onupgradeneeded = () => request.result.createObjectStore(fontStore);
  request.onsuccess = () => resolve(request.result);
  request.onerror = () => reject(request.error);
});

async function storedFonts() {
  const database = await openFonts();
  return new Promise<Set<string>>((resolve, reject) => {
    const request = database.transaction(fontStore).objectStore(fontStore).getAllKeys();
    request.onsuccess = () => { resolve(new Set(request.result.map(String))); database.close(); };
    request.onerror = () => { reject(request.error); database.close(); };
  });
}

async function storedFont(name: string) {
  const database = await openFonts();
  return new Promise<Blob | undefined>((resolve, reject) => {
    const request = database.transaction(fontStore).objectStore(fontStore).get(name);
    request.onsuccess = () => { resolve(request.result); database.close(); };
    request.onerror = () => { reject(request.error); database.close(); };
  });
}

async function saveFont(name: string, blob: Blob) {
  const database = await openFonts();
  return new Promise<void>((resolve, reject) => {
    const transaction = database.transaction(fontStore, "readwrite");
    transaction.objectStore(fontStore).put(blob, name);
    transaction.oncomplete = () => { resolve(); database.close(); };
    transaction.onerror = () => { reject(transaction.error); database.close(); };
  });
}

export async function cachedFontURL(name: string) {
  if (!name || !("indexedDB" in window)) return "";
  const blob = await storedFont(name);
  return blob ? URL.createObjectURL(blob) : "";
}

export function FontLibrary({ selected, onSelect, upload = false }: { selected?: FontChoice; onSelect?: (choice: FontChoice) => void; upload?: boolean }) {
  const [fonts, setFonts] = useState<ServerFont[]>([]);
  const [local, setLocal] = useState<Set<string>>(new Set());
  const [progress, setProgress] = useState<Record<string, number>>({});
  const [page, setPage] = useState(0);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const load = useCallback(async () => {
    const result = await api<{ fonts: ServerFont[] }>("/api/fonts");
    setFonts(result.fonts);
    if ("indexedDB" in window) setLocal(await storedFonts());
  }, []);
  useEffect(() => { load().catch((reason) => setError(reason.message)); }, [load]);

  const entries = useMemo(() => [
    ...builtInFonts.map((font) => ({ kind: "builtin" as const, ...font })),
    ...fonts.map((font) => ({ kind: "server" as const, ...font })),
  ], [fonts]);
  const pageCount = Math.max(1, Math.ceil(entries.length / pageSize));
  const currentPage = Math.min(page, pageCount - 1);
  const shown = entries.slice(currentPage * pageSize, currentPage * pageSize + pageSize);

  const download = async (font: ServerFont) => {
    setError("");
    setProgress((values) => ({ ...values, [font.name]: 0 }));
    try {
      if (!("indexedDB" in window)) throw new Error("当前浏览器不支持本地字体存储");
      const blob = await new Promise<Blob>((resolve, reject) => {
        const request = new XMLHttpRequest();
        request.open("GET", font.url);
        request.responseType = "blob";
        request.onprogress = (event) => setProgress((values) => ({ ...values, [font.name]: Math.min(1, event.loaded / Math.max(1, event.total || font.size)) }));
        request.onload = () => request.status >= 200 && request.status < 300 ? resolve(request.response) : reject(new Error("字体下载失败"));
        request.onerror = () => reject(new Error("字体下载失败"));
        request.send();
      });
      await saveFont(font.name, blob);
      setLocal((names) => new Set(names).add(font.name));
      setProgress((values) => { const next = { ...values }; delete next[font.name]; return next; });
      onSelect?.({ font: "custom", fontFile: font.name });
    } catch (reason) {
      setProgress((values) => { const next = { ...values }; delete next[font.name]; return next; });
      setError(reason instanceof Error ? reason.message : "字体下载失败");
    }
  };

  const uploadFont = async (event: FormEvent<HTMLInputElement>) => {
    const file = event.currentTarget.files?.[0];
    if (!file) return;
    setBusy(true);
    setError("");
    const form = new FormData();
    form.append("font", file);
    try {
      await api("/api/fonts", { method: "POST", body: form });
      await load();
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : "字体上传失败");
    } finally {
      setBusy(false);
      event.currentTarget.value = "";
    }
  };

  return <div className="font-library">
    {upload && <label className="font-upload"><Upload size={15} />{busy ? "正在上传…" : "上传字体到服务端"}<input disabled={busy} type="file" accept=".ttf,.otf,.woff,.woff2" onInput={uploadFont} /></label>}
    <div className="font-list">{shown.map((font) => {
      const builtin = font.kind === "builtin";
      const downloaded = builtin || local.has(font.name);
      const loading = font.kind === "server" ? progress[font.name] : undefined;
      const active = builtin ? selected?.font === font.id : selected?.font === "custom" && selected.fontFile === font.name;
      return <button key={builtin ? font.id : font.name} type="button" className={`${downloaded ? "local" : "remote"} ${active ? "active" : ""}`} aria-label={downloaded ? `使用${font.name}` : `下载${font.name}`} onClick={() => {
        if (builtin) onSelect?.({ font: font.id, fontFile: "" });
        else if (downloaded) onSelect?.({ font: "custom", fontFile: font.name });
        else if (loading === undefined) void download(font);
      }}>
        <span className="font-sample">Aa</span><span><strong>{font.name}</strong><small>{builtin ? font.detail : `${formatSize(font.size)} · ${downloaded ? "已下载至本地" : "服务端字体"}`}</small></span>
        {loading !== undefined ? <i className="font-progress" style={{ "--progress": `${Math.round(loading * 360)}deg` } as CSSProperties}><small>{Math.round(loading * 100)}</small></i> : downloaded ? <Check size={17} /> : <Download size={17} />}
      </button>;
    })}</div>
    {!entries.length && <p className="font-empty">服务端还没有字体</p>}
    {error && <p className="font-error">{error}</p>}
    <footer className="font-pagination"><button type="button" aria-label="上一页字体" disabled={currentPage === 0} onClick={() => setPage(currentPage - 1)}><ChevronLeft size={16} /></button><span>{currentPage + 1} / {pageCount}</span><button type="button" aria-label="下一页字体" disabled={currentPage + 1 === pageCount} onClick={() => setPage(currentPage + 1)}><ChevronRight size={16} /></button></footer>
  </div>;
}

const formatSize = (size: number) => size < 1024 * 1024 ? `${Math.max(1, Math.round(size / 1024))} KB` : `${(size / 1024 / 1024).toFixed(1)} MB`;
