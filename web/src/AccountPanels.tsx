import { BarChart3, CaseUpper, ChevronDown, ChevronRight, Folder, HardDrive, LoaderCircle, Palette, Plus, RefreshCw, Settings, Trash2, UserRound, X } from "lucide-react";
import { CSSProperties, FormEvent, useCallback, useEffect, useMemo, useState } from "react";
import { FontLibrary } from "./Fonts";
import { Bookshelf, ReadingStats, ServerInfo, api } from "./model";
import { AppTheme, selectPalette, themePalettes } from "./theme";

export type AccountPanel = "theme" | "fonts" | "stats" | "server" | "shelves";

export function AccountMenu({ onOpen }: { onOpen: (panel: AccountPanel) => void }) {
  const [open, setOpen] = useState(false);
  return <div className="account-menu">
    {open && <div className="account-popover">
      <button onClick={() => onOpen("theme")}><Palette size={16} />界面主题</button>
      <button onClick={() => onOpen("fonts")}><CaseUpper size={16} />字体库</button>
      <button onClick={() => onOpen("stats")}><BarChart3 size={16} />阅读数据</button>
      <button onClick={() => onOpen("server")}><HardDrive size={16} />后端连接</button>
      <button onClick={() => onOpen("shelves")}><Settings size={16} />书架设置</button>
    </div>}
    <button className="account-trigger" aria-expanded={open} onClick={() => setOpen((value) => !value)}><span><UserRound size={18} /></span><div><strong>Alumos</strong><small>书库管理员</small></div><ChevronDown size={15} /></button>
  </div>;
}

export function UserPanel({ kind, server, theme, onTheme, onClose, onSaved }: { kind: AccountPanel; server?: ServerInfo; theme: AppTheme; onTheme: (theme: AppTheme) => void; onClose: () => void; onSaved: () => Promise<void> }) {
  const titles = { theme: ["界面主题", "微光阅外观"], fonts: ["字体库", "本地字体管理"], stats: ["阅读数据", "你的阅读足迹"], server: ["后端连接", "当前服务端"], shelves: ["书架设置", "扫描目录"] } as const;
  return <div className="modal-backdrop" onMouseDown={(event) => event.target === event.currentTarget && onClose()}>
    <section className="user-panel" role="dialog" aria-modal="true" aria-label={titles[kind][0]}>
      <header><div><small>{titles[kind][0]}</small><h2>{titles[kind][1]}</h2></div><button aria-label="关闭" onClick={onClose}><X size={18} /></button></header>
      {kind === "theme" && <ThemeSettings theme={theme} onTheme={onTheme} />}
      {kind === "fonts" && <FontLibrary upload />}
      {kind === "stats" && <StatsPanel />}
      {kind === "server" && <ServerPanel server={server} />}
      {kind === "shelves" && <ShelvesPanel onSaved={onSaved} onClose={onClose} />}
    </section>
  </div>;
}

function ThemeSettings({ theme, onTheme }: { theme: AppTheme; onTheme: (theme: AppTheme) => void }) {
  const patch = (value: Partial<AppTheme>) => onTheme({ ...theme, palette: "custom", ...value });
  return <div className="theme-settings">
    <label>界面主题<select value={theme.mode} onChange={(event) => onTheme({ ...theme, mode: event.target.value as AppTheme["mode"] })}><option value="monet">莫奈柔彩</option><option value="eink">E-INK 黑白</option></select></label>
    {theme.mode !== "eink" && <>
      <div className="theme-presets"><span>莫奈调色盘</span><div>{themePalettes.map((palette) => <button key={palette.id} type="button" className={theme.palette === palette.id ? "active" : ""} aria-label={`使用${palette.name}配色`} onClick={() => onTheme(selectPalette(theme, palette))}><i style={{ background: `linear-gradient(135deg, ${palette.accent}, ${palette.secondary})` }} /><strong>{palette.name}</strong><small>{palette.hint}</small></button>)}</div></div>
      <label className="color-field">主色<input aria-label="界面主色" type="color" value={theme.accent} onChange={(event) => patch({ accent: event.target.value })} /></label>
      <label className="color-field">辅色<input aria-label="界面渐变色" type="color" value={theme.secondary} onChange={(event) => patch({ secondary: event.target.value })} /></label>
    </>}
  </div>;
}

function StatsPanel() {
  const [stats, setStats] = useState<ReadingStats>();
  useEffect(() => { api<ReadingStats>("/api/stats").then(setStats); }, []);
  if (!stats) return <div className="panel-loading"><LoaderCircle className="spin" />正在统计…</div>;
  const maximum = Math.max(1, ...(stats.days ?? []).map((day) => day.seconds));
  return <div className="stats-panel">
    <div className="stat-cards"><div><small>今日阅读</small><strong>{formatDuration(stats.today_seconds)}</strong></div><div><small>累计阅读</small><strong>{formatDuration(stats.total_seconds)}</strong></div></div>
    <section><h3>近 7 日</h3><div className="reading-bars">{(stats.days ?? []).length ? stats.days!.map((day) => <div key={day.date}><i style={{ height: `${Math.max(8, day.seconds / maximum * 100)}%` }} /><small>{day.date.slice(5)}</small></div>) : <p>开始阅读后，这里会出现趋势。</p>}</div></section>
    <section><h3>读得最多</h3><div className="reading-ranking">{(stats.books ?? []).map((book, index) => <div key={book.book_id}><span>{index + 1}</span><strong>{book.title ?? "已移出书库"}</strong><small>{formatDuration(book.seconds)}</small></div>)}</div></section>
  </div>;
}

function ServerPanel({ server }: { server?: ServerInfo }) {
  return <div className="server-panel">
    <div><small>服务端地址</small><strong>{window.location.origin}</strong></div>
    <div><small>服务状态</small><strong><i /> 已连接 · API v{server?.api_version}</strong></div>
    <div><small>支持格式</small><strong>{server?.formats.map((format) => format.toUpperCase()).join(" · ")}</strong></div>
    <p>Android 客户端只需填写这个地址，不直接连接 NAS。</p>
  </div>;
}

function ShelvesPanel({ onSaved, onClose }: { onSaved: () => Promise<void>; onClose: () => void }) {
  const [shelves, setShelves] = useState<Bookshelf[]>([]);
  const [directories, setDirectories] = useState<string[]>([]);
  const [automatic, setAutomatic] = useState(false);
  const [busy, setBusy] = useState(true);
  const [error, setError] = useState("");
  const [picking, setPicking] = useState<number>();
  const refreshDirectories = useCallback(() => api<{ directories: string[] }>("/api/shelves").then((result) => setDirectories(result.directories)), []);
  useEffect(() => {
    let active = true;
    api<{ shelves: Bookshelf[] | null; directories: string[]; automatic: boolean }>("/api/shelves").then((result) => {
      if (!active) return;
      setShelves(result.shelves ?? []); setDirectories(result.directories); setAutomatic(result.automatic);
    }).catch((reason) => setError(reason.message)).finally(() => setBusy(false));
    const refresh = () => refreshDirectories().catch(() => undefined);
    const timer = window.setInterval(refresh, 15000);
    window.addEventListener("focus", refresh);
    return () => { active = false; window.clearInterval(timer); window.removeEventListener("focus", refresh); };
  }, [refreshDirectories]);
  const save = async (event: FormEvent) => {
    event.preventDefault(); setBusy(true); setError("");
    try { await api("/api/shelves", { method: "PUT", body: JSON.stringify({ shelves }) }); await onSaved(); onClose(); }
    catch (reason) { setError(reason instanceof Error ? reason.message : "保存失败"); setBusy(false); }
  };
  return <form className="shelves-panel" onSubmit={save}>
    <div className="directory-status"><p>{automatic && !shelves.length ? "当前按挂载目录自动生成书架。添加配置后，只扫描指定目录。" : `目录相对于只读挂载的书库根目录，已发现 ${directories.length} 个。`}</p><button type="button" onClick={() => refreshDirectories().catch((reason) => setError(reason.message))}><RefreshCw size={14} />刷新目录</button></div>
    <div className="shelf-settings-list">{shelves.map((shelf, index) => <div key={index} className="shelf-setting">
      <label>书架名称<input value={shelf.name} maxLength={64} onChange={(event) => setShelves((current) => current.map((item, itemIndex) => itemIndex === index ? { ...item, name: event.target.value } : item))} placeholder="例如：漫画" /></label>
      <label>内容类型<select aria-label={`${shelf.name || `书架 ${index + 1}`}内容类型`} value={shelf.kind} onChange={(event) => setShelves((current) => current.map((item, itemIndex) => itemIndex === index ? { ...item, kind: event.target.value as Bookshelf["kind"] } : item))}><option value="auto">自动识别</option><option value="book">图书</option><option value="comic">漫画</option></select></label>
      <button type="button" aria-label="删除书架" onClick={() => setShelves((current) => current.filter((_, itemIndex) => itemIndex !== index))}><Trash2 size={16} /></button>
      <label className="directory-field">扫描目录<div><input value={shelf.path} onFocus={() => refreshDirectories().catch(() => undefined)} onChange={(event) => setShelves((current) => current.map((item, itemIndex) => itemIndex === index ? { ...item, path: event.target.value } : item))} placeholder="例如：漫画/爱情" /><button type="button" aria-expanded={picking === index} onClick={() => { setPicking((current) => current === index ? undefined : index); refreshDirectories().catch(() => undefined); }}><Folder size={14} />目录树</button></div></label>
      {picking === index && <DirectoryPicker directories={directories} value={shelf.path} onChange={(path) => setShelves((current) => current.map((item, itemIndex) => itemIndex === index ? { ...item, path } : item))} />}
    </div>)}</div>
    <button type="button" className="add-shelf" onClick={() => setShelves((current) => [...current, { name: "", path: directories.find((path) => path !== ".") ?? ".", kind: "auto" }])}><Plus size={16} />添加书架</button>
    {error && <p className="error">{error}</p>}
    <button className="button primary" disabled={busy}>{busy ? "正在保存…" : shelves.length ? "保存并重新扫描" : "恢复自动书架"}</button>
  </form>;
}

function DirectoryPicker({ directories, value, onChange }: { directories: string[]; value: string; onChange: (path: string) => void }) {
  const items = useMemo(() => [...new Set(directories)].sort((a, b) => a === "." ? -1 : b === "." ? 1 : a.localeCompare(b, "zh-CN", { numeric: true })), [directories]);
  const parents = useMemo(() => new Set(items.map(directoryParent).filter(Boolean)), [items]);
  const [expanded, setExpanded] = useState(() => new Set([".", ...directoryParents(value)]));
  const visible = items.filter((path) => path === "." || directoryParents(path).every((parent) => expanded.has(parent)));
  return <div className="directory-tree" role="tree" aria-label="实时书库目录">
    {visible.map((path) => {
      const children = parents.has(path); const open = expanded.has(path); const depth = path === "." ? 0 : path.split("/").length;
      return <div key={path} className={`directory-node ${path === value ? "selected" : ""}`} role="treeitem" aria-level={depth + 1} aria-selected={path === value} style={{ paddingLeft: depth * 15 } as CSSProperties}>
        {children ? <button type="button" className="directory-toggle" aria-label={`${open ? "收起" : "展开"}${path === "." ? "书库根目录" : path}`} aria-expanded={open} onClick={() => setExpanded((current) => { const next = new Set(current); if (open) next.delete(path); else next.add(path); return next; })}>{open ? <ChevronDown size={14} /> : <ChevronRight size={14} />}</button> : <span className="directory-toggle" />}
        <button type="button" className="directory-select" onClick={() => onChange(path)}><Folder size={15} /><span>{path === "." ? "书库根目录" : path.split("/").at(-1)}</span></button>
      </div>;
    })}
  </div>;
}

function directoryParent(path: string) {
  if (path === ".") return "";
  const parts = path.split("/");
  return parts.length === 1 ? "." : parts.slice(0, -1).join("/");
}

function directoryParents(path: string) {
  const parents: string[] = [];
  for (let parent = directoryParent(path); parent; parent = directoryParent(parent)) parents.unshift(parent);
  return parents;
}

function formatDuration(seconds: number) {
  if (seconds < 60) return `${seconds} 秒`;
  const hours = Math.floor(seconds / 3600);
  const minutes = Math.floor(seconds % 3600 / 60);
  return hours ? `${hours} 小时 ${minutes} 分` : `${minutes} 分钟`;
}
