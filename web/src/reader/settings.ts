import { ReadingSettings, ReadingTemplate } from "./types";

type LegacyTheme = "paper" | "clean" | "eink" | "night";

export const defaultSettings: ReadingSettings = {
  template: "literary",
  backgroundColor: "#f7f1e4",
  textColor: "#29261f",
  font: "book",
  fontFile: "",
  fontSize: 19,
  lineHeight: 1.8,
  paragraphSpacing: 0.8,
  letterSpacing: 0,
  columns: 1,
  animation: "slide",
  readingDirection: "auto",
  showPageButtons: true,
};

export const withoutTemplate = (settings: ReadingSettings): Omit<ReadingSettings, "template"> => {
  const { template: _template, ...values } = settings;
  return values;
};

export const builtInTemplates: ReadingTemplate[] = [
  { id: "web", name: "网文阅读", hint: "大字舒展", settings: { ...withoutTemplate(defaultSettings), backgroundColor: "#ffffff", textColor: "#202522", fontSize: 21, lineHeight: 1.95, paragraphSpacing: 1, letterSpacing: 0.02 } },
  { id: "literary", name: "精品文学", hint: "纸感留白", settings: withoutTemplate(defaultSettings) },
  { id: "eink", name: "墨水屏", hint: "纯黑无动画", settings: { ...withoutTemplate(defaultSettings), backgroundColor: "#ffffff", textColor: "#000000", fontSize: 20, lineHeight: 1.75, animation: "none" } },
  { id: "comic", name: "漫画优化", hint: "日漫右翻", settings: { ...withoutTemplate(defaultSettings), backgroundColor: "#ffffff", textColor: "#000000", columns: 2, animation: "none", readingDirection: "rtl" } },
  { id: "night", name: "夜间阅读", hint: "低亮深色", settings: { ...withoutTemplate(defaultSettings), backgroundColor: "#161a18", textColor: "#d9ddd9", lineHeight: 1.85 } },
];

export function loadSettings(): ReadingSettings {
  try {
    return migrateSettings(JSON.parse(localStorage.getItem("lumos-reading-settings") ?? "{}"));
  } catch {
    return defaultSettings;
  }
}

export function loadTemplates(): ReadingTemplate[] {
  try {
    const value = JSON.parse(localStorage.getItem("lumos-reading-templates") ?? "[]");
    return Array.isArray(value) ? value.slice(0, 12).map((template) => ({ ...template, settings: withoutTemplate(migrateSettings({ ...template.settings, template: template.id })) })) : [];
  } catch {
    return [];
  }
}

function migrateSettings(saved: Partial<ReadingSettings> & { theme?: LegacyTheme }): ReadingSettings {
  const oldTheme = saved.theme ?? (["paper", "clean", "eink", "night"].includes(saved.template ?? "") ? saved.template as LegacyTheme : "paper");
  const colors = oldTheme === "night" ? { background: "#161a18", foreground: "#d9ddd9" }
    : oldTheme === "eink" ? { background: "#ffffff", foreground: "#000000" }
    : oldTheme === "clean" ? { background: "#ffffff", foreground: "#202522" }
    : { background: "#f7f1e4", foreground: "#29261f" };
  const { theme: _theme, ...current } = saved;
  return {
    ...defaultSettings,
    ...current,
    backgroundColor: saved.backgroundColor ?? colors.background,
    textColor: saved.textColor ?? colors.foreground,
    font: saved.font === "custom" && !saved.fontFile ? "serif" : saved.font ?? defaultSettings.font,
  };
}

export function fontFamily(font: ReadingSettings["font"]) {
  if (font === "sans") return '"Noto Sans CJK SC", "Microsoft YaHei", sans-serif';
  if (font === "system") return '-apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif';
  if (font === "custom") return "LumosCustom, serif";
  return '"Noto Serif CJK SC", "Songti SC", "STSong", serif';
}
