export type ServerInfo = {
  name: string;
  version: string;
  api_version: number;
  auth_required: boolean;
  formats: string[];
};

export type Book = {
  id: string;
  title: string;
  file_name: string;
  author?: string;
  format: "epub" | "mobi" | "azw3" | "pdf" | "cbz" | "txt";
  shelf: string;
  shelf_kind: "book" | "comic";
  category?: string;
  series?: string;
  is_comic: boolean;
  page_direction?: "ltr" | "rtl";
  size: number;
  modified: string;
  progress: number;
  locator?: string;
  progress_time?: string;
  cover_url?: string;
};

export type ComicPage = { index: number; name: string; url: string };
export type ServerFont = { name: string; url: string; size: number };

export type Bookshelf = { name: string; path: string; kind: "auto" | "book" | "comic" };

export type ReadingStats = {
  total_seconds: number;
  today_seconds: number;
  days: { date: string; seconds: number }[] | null;
  books: { book_id: string; title?: string; seconds: number }[] | null;
};

export async function api<T>(path: string, init?: RequestInit): Promise<T> {
  const jsonBody = init?.body && !(init.body instanceof FormData);
  const response = await fetch(path, {
    credentials: "same-origin",
    ...init,
    headers: jsonBody ? { "Content-Type": "application/json", ...init?.headers } : init?.headers,
  });
  if (!response.ok) {
    const body = await response.json().catch(() => ({ error: "请求失败" }));
    throw new Error(body.error ?? "请求失败");
  }
  return response.status === 204 ? (undefined as T) : response.json();
}

class RemoteSlice {
  constructor(
    private readonly url: string,
    private readonly start: number,
    readonly size: number,
    readonly type: string,
  ) {}

  async arrayBuffer() {
    if (this.size <= 0) return new ArrayBuffer(0);
    const response = await fetch(this.url, {
      credentials: "same-origin",
      headers: { Range: `bytes=${this.start}-${this.start + this.size - 1}` },
    });
    if (response.status !== 206 && !response.ok) throw new Error(`读取书籍失败：${response.status}`);
    const buffer = await response.arrayBuffer();
    return response.status === 206 ? buffer : buffer.slice(this.start, this.start + this.size);
  }

  async text() {
    return new TextDecoder().decode(await this.arrayBuffer());
  }
}

// File-compatible ranges let foliate-js read archives and Kindle files without downloading them whole.
export class RemoteFile {
  readonly lastModified = Date.now();

  constructor(
    readonly url: string,
    readonly name: string,
    readonly size: number,
    readonly type: string,
  ) {}

  slice(start = 0, end = this.size, type = this.type) {
    const from = Math.max(0, Math.min(this.size, start < 0 ? this.size + start : start));
    const to = Math.max(from, Math.min(this.size, end < 0 ? this.size + end : end));
    return new RemoteSlice(this.url, from, to - from, type);
  }

  arrayBuffer() {
    return this.slice().arrayBuffer();
  }
}

export function contentURL(book: Book) {
  return `/api/books/${book.id}/content`;
}

export function fileStem(name: string) {
  return name.replace(/\.[^./]+$/, "");
}

export function saveProgress(bookID: string, position: number, locator: string, keepalive = false) {
  return api(`/api/books/${bookID}/progress`, {
    method: "PUT",
    body: JSON.stringify({ position: Math.max(0, Math.min(1, position)), locator }),
    keepalive,
  });
}

export function addReadingTime(bookID: string, seconds: number) {
  return api(`/api/books/${bookID}/reading-time`, {
    method: "POST",
    body: JSON.stringify({ seconds }),
    keepalive: true,
  });
}
