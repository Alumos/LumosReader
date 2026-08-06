declare module "foliate-js/view.js" {
  export function makeBook(file: unknown): Promise<{
    getCover?: () => Promise<Blob | null>;
    dir?: "ltr" | "rtl";
  }>;
}

interface FoliateRenderer extends HTMLElement {
  setStyles?: (styles: string) => void;
}

interface FoliateTOCItem {
  label?: string;
  href?: string;
  subitems?: FoliateTOCItem[];
}

interface FoliateSection {
  load?: () => Promise<unknown>;
  unload?: () => void;
}

interface FoliateViewElement extends HTMLElement {
  renderer?: FoliateRenderer;
  book?: { toc?: FoliateTOCItem[]; sections?: FoliateSection[] };
  open: (book: unknown) => Promise<void>;
  init: (options: { lastLocation?: unknown; showTextStart?: boolean }) => Promise<void>;
  prev: () => Promise<void>;
  next: () => Promise<void>;
  goLeft: () => Promise<void>;
  goRight: () => Promise<void>;
  goTo: (target: unknown) => Promise<unknown>;
  goToFraction: (fraction: number) => Promise<void>;
  close: () => void;
}
