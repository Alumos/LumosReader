import { BookOpen, ChevronLeft, ChevronRight, LoaderCircle } from "lucide-react";

export function PageZones({ visible, onLeft, onCenter, onRight }: { visible: boolean; onLeft: () => void; onCenter: () => void; onRight: () => void }) {
  return <>
    <button className={`tap-zone left ${visible ? "page-button-visible" : ""}`} aria-label="上一页" onClick={onLeft}>{visible && <span><ChevronLeft /></span>}</button>
    <button className="tap-zone center" aria-label="显示阅读菜单" onClick={onCenter} />
    <button className={`tap-zone right ${visible ? "page-button-visible" : ""}`} aria-label="下一页" onClick={onRight}>{visible && <span><ChevronRight /></span>}</button>
  </>;
}

export function ReaderLoading({ label = "正在排版…" }: { label?: string }) {
  return <div className="reader-loading"><LoaderCircle className="spin" /><span>{label}</span></div>;
}

export function ReaderFailure({ message }: { message: string }) {
  return <div className="reader-message"><BookOpen size={40} /><h2>载入失败</h2><p>{message}</p></div>;
}
