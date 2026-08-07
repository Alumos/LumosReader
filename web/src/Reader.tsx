import { ArrowLeft, ChevronLeft, ChevronRight, ListTree, LocateFixed, Save, Settings2, Trash2, X } from "lucide-react";
import { CSSProperties, useCallback, useEffect, useState } from "react";
import { cachedFontURL, FontLibrary } from "./Fonts";
import { Book, fileStem } from "./model";
import { installFoliatePolyfills } from "./reader/compat";
import { ComicReader, PDFReader, TextReader } from "./reader/DocumentReaders";
import { FoliateReader } from "./reader/FoliateReader";
import { useReadingTimer } from "./reader/hooks";
import { builtInTemplates, loadSettings, loadTemplates, withoutTemplate } from "./reader/settings";
import { Chapter, ReaderControls, ReadingSettings, ReadingTemplate } from "./reader/types";

installFoliatePolyfills();

const previousPageKeys = new Set(["ArrowLeft", "AudioVolumeUp", "VolumeUp"]);
const nextPageKeys = new Set(["ArrowRight", "AudioVolumeDown", "VolumeDown"]);
type ChromeItem =
  | { kind: "volume"; book: Book; label: string; number: number }
  | { kind: "chapter"; chapter: Chapter; label: string; number: number };

export function Reader({ book, collection, onBook, onClose }: { book: Book; collection: Book[]; onBook: (book: Book) => void; onClose: () => void }) {
  const [progress, setProgress] = useState(book.progress);
  const [settings, setSettings] = useState(loadSettings);
  const [customTemplates, setCustomTemplates] = useState(loadTemplates);
  const [panel, setPanel] = useState<"menu" | "settings">();
  const [controls, setControls] = useState<ReaderControls>();
  const [customFontURL, setCustomFontURL] = useState("");
  const imageComic = book.format === "cbz";
  const visualPages = imageComic || Boolean(book.fixed_layout);
  const styleable = !book.fixed_layout && (["epub", "mobi", "azw3", "txt"] as Book["format"][]).includes(book.format);
  const direction = settings.readingDirection === "auto" ? book.page_direction ?? "ltr" : settings.readingDirection;
  const connectControls = useCallback((value?: ReaderControls) => setControls(value), []);
  const toggleMenu = useCallback(() => setPanel((current) => current === "menu" ? undefined : "menu"), []);

  useReadingTimer(book.id);
  useEffect(() => { setProgress(book.progress); setPanel(undefined); }, [book]);
  useEffect(() => { localStorage.setItem("lumos-reading-settings", JSON.stringify(settings)); }, [settings]);
  useEffect(() => { localStorage.setItem("lumos-reading-templates", JSON.stringify(customTemplates)); }, [customTemplates]);
  useEffect(() => {
    let active = true;
    let url = "";
    if (settings.font !== "custom" || !settings.fontFile) { setCustomFontURL(""); return; }
    cachedFontURL(settings.fontFile).then((value) => {
      url = value;
      if (!active) { if (value) URL.revokeObjectURL(value); return; }
      if (value) setCustomFontURL(value);
      else setSettings((current) => ({ ...current, font: "book", fontFile: "" }));
    });
    return () => { active = false; if (url) URL.revokeObjectURL(url); };
  }, [settings.font, settings.fontFile]);
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

  return <div className="reader" style={{ "--reader-bg": settings.backgroundColor, "--reader-fg": settings.textColor } as CSSProperties}>
    <button className="reader-exit" aria-label="返回书库" onClick={onClose}><ArrowLeft size={18} /><span>书库</span></button>
    <div className="reader-body">
      {imageComic && <ComicReader book={book} settings={settings} direction={direction} onCenter={toggleMenu} onControls={connectControls} onProgress={setProgress} />}
      {book.format === "txt" && <TextReader book={book} settings={settings} customFontURL={customFontURL} onCenter={toggleMenu} onControls={connectControls} onProgress={setProgress} />}
      {!imageComic && (book.format === "epub" || book.format === "mobi" || book.format === "azw3") && <FoliateReader book={book} settings={settings} customFontURL={customFontURL} onCenter={toggleMenu} onControls={connectControls} onProgress={setProgress} />}
      {book.format === "pdf" && <PDFReader book={book} settings={settings} onCenter={toggleMenu} onControls={connectControls} onProgress={setProgress} />}
    </div>
    <small className="reader-location">{controls?.location ?? `${Math.round(progress * 100)}%`}</small>
    {panel === "menu" && <ReaderChrome book={book} collection={collection} progress={progress} controls={controls} onBook={onBook} onSettings={() => setPanel("settings")} onLibrary={onClose} />}
    {panel === "settings" && <SettingsPanel
      settings={settings}
      templates={[...builtInTemplates, ...customTemplates]}
      styleable={styleable}
      visualPages={visualPages}
      onChange={setSettings}
      onClose={() => setPanel(undefined)}
      onDelete={(id) => setCustomTemplates((templates) => templates.filter((template) => template.id !== id))}
      onSave={(name) => {
        const id = `custom-${Date.now()}`;
        setCustomTemplates((templates) => [...templates, { id, name, hint: "我的排版", settings: withoutTemplate(settings), custom: true }]);
        setSettings((current) => ({ ...current, template: id }));
      }}
    />}
  </div>;
}

function ReaderChrome({ book, collection, progress, controls, onBook, onSettings, onLibrary }: { book: Book; collection: Book[]; progress: number; controls?: ReaderControls; onBook: (book: Book) => void; onSettings: () => void; onLibrary: () => void }) {
  const [query, setQuery] = useState("");
  const [descending, setDescending] = useState(false);
  const [page, setPage] = useState(0);
  const [chaptersOpen, setChaptersOpen] = useState(false);
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
  const locateCurrent = () => {
    const index = chapters.findIndex((chapter) => chapter.href === controls?.currentChapter);
    if (index < 0) return;
    setQuery(""); setDescending(false); setPage(Math.floor((volumes.length + index) / 20));
  };
  return <div className="reader-chrome">
    {chaptersOpen && (volumes.length > 0 || chapters.length > 0) && <aside className="chapter-panel">
      <header><ListTree size={17} /><strong>章节与卷册</strong><button aria-label="收起" onClick={() => setChaptersOpen(false)}><X size={16} /></button></header>
      <div className="chapter-tools"><input type="search" aria-label="搜索章节或卷册" value={query} onChange={(event) => { setQuery(event.target.value); setPage(0); }} placeholder="搜索章节或卷册" /><button aria-label="定位当前章节" disabled={!controls?.currentChapter} onClick={locateCurrent}><LocateFixed size={13} />定位</button><button aria-label="切换排列顺序" onClick={() => { setDescending((value) => !value); setPage(0); }}>{descending ? "倒序" : "顺序"}</button></div>
      <div className="chapter-scroll">
        {shown.some((item) => item.kind === "volume") && <section className="chapter-list"><small>卷册</small>{shown.map((item) => item.kind === "volume" && <button key={item.book.id} className={item.book.id === book.id ? "active" : ""} onClick={() => onBook(item.book)}><span>{item.number}</span>{item.label}</button>)}</section>}
        {shown.some((item) => item.kind === "chapter") && <section className="chapter-list"><small>目录</small>{shown.map((item) => item.kind === "chapter" && <button key={`${item.chapter.href}-${item.number}`} className={item.chapter.href === controls?.currentChapter ? "active" : ""} style={{ "--level": item.chapter.level } as CSSProperties} onClick={() => controls?.goToChapter?.(item.chapter.href)}><span>{item.number}</span>{item.label}</button>)}</section>}
        {!shown.length && <p className="chapter-empty">没有匹配的章节或卷册</p>}
      </div>
      <footer className="chapter-pagination"><button aria-label="上一页目录" disabled={currentPage === 0} onClick={() => setPage(currentPage - 1)}><ChevronLeft size={16} /></button><span>{currentPage + 1} / {pageCount}</span><button aria-label="下一页目录" disabled={currentPage + 1 === pageCount} onClick={() => setPage(currentPage + 1)}><ChevronRight size={16} /></button></footer>
    </aside>}
    <div className="floating-reader-head"><strong>{book.title}</strong><small>{book.format.toUpperCase()}</small></div>
    <div className="floating-reader-bar">
      <button className="reader-menu-button" aria-label="返回书库" onClick={onLibrary}><ArrowLeft size={17} /><span>书库</span></button>
      <button aria-label="上一页" onClick={controls?.previous}><ChevronLeft /></button>
      <div><span>{Math.round(progress * 100)}%</span><input aria-label="阅读进度" type="range" min={0} max={1} step={0.001} value={progress} disabled={!controls?.seek} onChange={(event) => controls?.seek?.(Number(event.target.value))} /></div>
      <button aria-label="下一页" onClick={controls?.next}><ChevronRight /></button>
      <button className={`reader-menu-button ${chaptersOpen ? "active" : ""}`} aria-label="章节与卷册" aria-pressed={chaptersOpen} onClick={() => setChaptersOpen((open) => !open)}><ListTree size={17} /><span>章节</span></button>
      <button className="reader-menu-button" aria-label="阅读设置" onClick={onSettings}><Settings2 size={17} /><span>设置</span></button>
    </div>
  </div>;
}

function SettingsPanel({ settings, templates, styleable, visualPages, onChange, onClose, onSave, onDelete }: {
  settings: ReadingSettings;
  templates: ReadingTemplate[];
  styleable: boolean;
  visualPages: boolean;
  onChange: (value: ReadingSettings | ((current: ReadingSettings) => ReadingSettings)) => void;
  onClose: () => void;
  onSave: (name: string) => void;
  onDelete: (id: string) => void;
}) {
  const [templateName, setTemplateName] = useState("");
  const patch = (value: Partial<ReadingSettings>) => onChange((current) => ({ ...current, ...value }));
  return <aside className="settings-panel" aria-label="阅读设置">
    <header><div><small>阅读设置</small><strong>排版与翻页</strong></div><button aria-label="关闭设置" onClick={onClose}><X size={18} /></button></header>
    <div className="settings-scroll">
      <section><label>排版模板</label><div className="template-grid">{templates.map((template) => <div key={template.id} className="template-item">
        <button className={settings.template === template.id ? "active" : ""} onClick={() => onChange({ ...template.settings, template: template.id })}><span className="template-swatch" style={{ backgroundColor: template.settings.backgroundColor, color: template.settings.textColor }}>Aa</span><strong>{template.name}</strong><small>{template.hint}</small></button>
        {template.custom && <button className="delete-template" aria-label={`删除${template.name}`} onClick={() => onDelete(template.id)}><Trash2 size={12} /></button>}
      </div>)}</div>
        <form className="save-template" onSubmit={(event) => { event.preventDefault(); const name = templateName.trim(); if (name) { onSave(name); setTemplateName(""); } }}><input value={templateName} maxLength={24} onChange={(event) => setTemplateName(event.target.value)} placeholder="新模板名称" /><button disabled={!templateName.trim()}><Save size={14} />保存当前参数</button></form>
      </section>
      <section className="setting-fields">
        <label>正文字体</label>
        <FontLibrary selected={{ font: settings.font, fontFile: settings.fontFile }} onSelect={({ font, fontFile }) => patch({ font, fontFile })} />
        {styleable && <>
          <Range label="字号" value={settings.fontSize} min={14} max={32} step={1} suffix="px" onChange={(fontSize) => patch({ fontSize })} />
          <Range label="行间距" value={settings.lineHeight} min={1.2} max={2.4} step={0.05} onChange={(lineHeight) => patch({ lineHeight })} />
          <Range label="段间距" value={settings.paragraphSpacing} min={0} max={2} step={0.1} suffix="em" onChange={(paragraphSpacing) => patch({ paragraphSpacing })} />
          <Range label="字间距" value={settings.letterSpacing} min={-.05} max={.3} step={.01} suffix="em" onChange={(letterSpacing) => patch({ letterSpacing })} />
        </>}
        <label className="color-field">页面底色<input aria-label="页面底色" type="color" value={settings.backgroundColor} onChange={(event) => patch({ backgroundColor: event.target.value })} /></label>
        <label className="color-field">文字颜色<input aria-label="文字颜色" type="color" value={settings.textColor} onChange={(event) => patch({ textColor: event.target.value })} /></label>
        {(styleable || visualPages) && <label>页面模式<select value={settings.columns} onChange={(event) => patch({ columns: Number(event.target.value) as 1 | 2 })}><option value={1}>单页</option><option value={2}>双页（横屏）</option></select></label>}
        <label>翻页动画<select value={settings.animation} onChange={(event) => patch({ animation: event.target.value as ReadingSettings["animation"] })}><option value="none">无动画</option><option value="slide">平滑滑动</option><option value="fade">淡入淡出</option></select></label>
        <label className="toggle-field"><span>显示半透明翻页键</span><input type="checkbox" checked={settings.showPageButtons} onChange={(event) => patch({ showPageButtons: event.target.checked })} /></label>
        {visualPages && <label>阅读方向<select value={settings.readingDirection} onChange={(event) => patch({ readingDirection: event.target.value as ReadingSettings["readingDirection"] })}><option value="auto">自动识别</option><option value="rtl">从右向左</option><option value="ltr">从左向右</option></select></label>}
      </section>
    </div>
  </aside>;
}

function Range({ label, value, min, max, step, suffix = "", onChange }: { label: string; value: number; min: number; max: number; step: number; suffix?: string; onChange: (value: number) => void }) {
  return <label className="range-field"><span>{label}<output>{value}{suffix}</output></span><input type="range" value={value} min={min} max={max} step={step} onChange={(event) => onChange(Number(event.target.value))} /></label>;
}
