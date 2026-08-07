export type AppTheme = {
  mode: "monet" | "eink";
  palette: string;
  accent: string;
  secondary: string;
};

export const themePalettes = [
  { id: "water-lilies", name: "睡莲晨雾", hint: "湖蓝 · 鼠尾草", accent: "#47777a", secondary: "#c9aa83" },
  { id: "garden", name: "吉维尼花园", hint: "苔绿 · 杏粉", accent: "#55765f", secondary: "#d49a86" },
  { id: "dusk", name: "鲁昂黄昏", hint: "陶土 · 暮金", accent: "#945b50", secondary: "#d5a56f" },
  { id: "iris", name: "鸢尾微雨", hint: "靛青 · 雾紫", accent: "#596b8d", secondary: "#b69db8" },
] as const;

const fallback = themePalettes[0];
export const defaultAppTheme: AppTheme = { mode: "monet", palette: fallback.id, accent: fallback.accent, secondary: fallback.secondary };
const validHex = (value: unknown): value is string => typeof value === "string" && /^#[\da-f]{6}$/i.test(value);

export function loadAppTheme(): AppTheme {
  try {
    const saved = JSON.parse(localStorage.getItem("lumos-app-theme") ?? "{}");
    const palette = themePalettes.find((item) => item.id === saved.palette);
    return {
      // Both legacy color modes migrate to the single, more expressive Monet theme.
      mode: saved.mode === "eink" ? "eink" : "monet",
      palette: palette?.id ?? "custom",
      accent: validHex(saved.accent) ? saved.accent : palette?.accent ?? fallback.accent,
      secondary: validHex(saved.secondary) ? saved.secondary : palette?.secondary ?? fallback.secondary,
    };
  } catch {
    return defaultAppTheme;
  }
}

export function applyAppTheme(theme: AppTheme) {
  const root = document.documentElement;
  const eink = theme.mode === "eink";
  const accent = eink ? "#000000" : theme.accent;
  const secondary = eink ? "#000000" : theme.secondary;
  const alpha = (hex: string, opacity: number) => {
    const value = Number.parseInt(hex.slice(1), 16);
    return `rgba(${value >> 16}, ${(value >> 8) & 255}, ${value & 255}, ${opacity})`;
  };
  root.dataset.appTheme = theme.mode;
  root.style.setProperty("--green", accent);
  root.style.setProperty("--green-soft", alpha(accent, eink ? .12 : .14));
  root.style.setProperty("--theme-secondary", secondary);
  root.style.setProperty("--theme-gradient", eink ? "#ffffff" : `
    radial-gradient(circle at 82% 18%, ${alpha(secondary, .3)}, transparent 34%),
    radial-gradient(circle at 12% 88%, ${alpha(accent, .18)}, transparent 42%),
    linear-gradient(135deg, ${alpha(accent, .12)}, rgba(255,255,255,.88) 48%, ${alpha(secondary, .14)})`);
  root.style.setProperty("--app-bg", eink ? "#ffffff" : `
    radial-gradient(circle at 100% 0%, ${alpha(secondary, .1)}, transparent 30rem),
    linear-gradient(145deg, ${alpha(accent, .055)}, #f8f9f6 42%, ${alpha(secondary, .05)})`);
  root.style.setProperty("--ui-text", eink ? "#000000" : "#18201c");
  root.style.setProperty("--border", eink ? "#000000" : alpha(accent, .17));
  root.style.setProperty("--muted", eink ? "#444444" : "#68756d");
}

export function selectPalette(theme: AppTheme, palette: typeof themePalettes[number]): AppTheme {
  return { ...theme, mode: "monet", palette: palette.id, accent: palette.accent, secondary: palette.secondary };
}
