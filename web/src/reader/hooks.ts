import { useEffect } from "react";
import { addReadingTime } from "../model";

export function useReadingTimer(bookID: string) {
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
    const timer = window.setInterval(flush, 30000);
    const visibility = () => flush(true);
    document.addEventListener("visibilitychange", visibility);
    return () => {
      window.clearInterval(timer);
      document.removeEventListener("visibilitychange", visibility);
      flush(true);
    };
  }, [bookID]);
}
