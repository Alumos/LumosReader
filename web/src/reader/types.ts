export type ReadingSettings = {
  template: string;
  backgroundColor: string;
  textColor: string;
  font: "book" | "serif" | "sans" | "system" | "custom";
  fontFile: string;
  fontSize: number;
  lineHeight: number;
  paragraphSpacing: number;
  letterSpacing: number;
  columns: 1 | 2;
  animation: "none" | "slide" | "fade";
  readingDirection: "auto" | "ltr" | "rtl";
  showPageButtons: boolean;
};

export type ReadingTemplate = {
  id: string;
  name: string;
  hint: string;
  settings: Omit<ReadingSettings, "template">;
  custom?: boolean;
};

export type Chapter = { label: string; href: string; level: number };

export type ReaderControls = {
  previous: () => void;
  next: () => void;
  seek?: (progress: number) => void;
  chapters?: Chapter[];
  location?: string;
  currentChapter?: string;
  goToChapter?: (href: string) => void;
};

export type ReaderAdapterProps = {
  onCenter: () => void;
  onControls: (controls?: ReaderControls) => void;
  onProgress: (value: number) => void;
};
