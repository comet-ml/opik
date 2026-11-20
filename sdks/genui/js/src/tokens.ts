export type Theme = "light" | "dark";

const FONT =
  'Inter, ui-sans-serif, system-ui, -apple-system, "Segoe UI", sans-serif';
const FONT_MONO =
  '"Ubuntu Mono", ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, "Liberation Mono", "Courier New", monospace';

const SHARED = {
  "--ov-danger": "hsl(348 86% 61%)",
  "--ov-radius": "8px",
  "--ov-radius-sm": "4px",
  "--ov-font": FONT,
  "--ov-font-mono": FONT_MONO,
  "--ov-row-gap": "16px",
  "--ov-column-gap": "16px",
  "--ov-chart-1": "#945fcf",
  "--ov-chart-2": "#12a4b4",
  "--ov-chart-3": "#f4b400",
  "--ov-chart-4": "#ed4a7b",
  "--ov-chart-5": "#19a979",
  "--ov-chart-6": "#fb9341",
  "--ov-chart-7": "#bf399e",
  "--ov-chart-8": "#5899da",
};

export const TOKENS: Readonly<Record<Theme, Readonly<Record<string, string>>>> =
  {
    light: {
      "--ov-bg": "hsl(0 0% 100%)",
      "--ov-bg-subtle": "hsl(210 20% 98%)",
      "--ov-surface": "hsl(240 20% 99%)",
      "--ov-fg": "hsl(224 17% 26%)",
      "--ov-fg-strong": "hsl(224 71% 4%)",
      "--ov-fg-muted": "hsl(215 16% 47%)",
      "--ov-fg-subtle": "hsl(215 20% 65%)",
      "--ov-border": "hsl(214 32% 92%)",
      "--ov-accent": "hsl(239 89% 64%)",
      "--ov-accent-soft": "hsl(235 85% 97%)",
      "--ov-on-accent": "hsl(210 40% 98%)",
      "--ov-success": "hsl(142 64% 32%)",
      "--ov-warning": "hsl(32 85% 38%)",
      "--ov-warning-soft": "hsl(45 93% 89%)",
      ...SHARED,
    },
    dark: {
      "--ov-bg": "hsl(0 0% 7%)",
      "--ov-bg-subtle": "hsl(0 0% 11%)",
      "--ov-surface": "hsl(0 0% 7%)",
      "--ov-fg": "hsl(214 32% 91%)",
      "--ov-fg-strong": "hsl(214 32% 91%)",
      "--ov-fg-muted": "hsl(0 0% 64%)",
      "--ov-fg-subtle": "hsl(0 0% 64%)",
      "--ov-border": "hsl(0 0% 16%)",
      "--ov-accent": "hsl(223 91% 60%)",
      "--ov-accent-soft": "hsl(222 25% 14%)",
      "--ov-on-accent": "hsl(0 0% 11%)",
      "--ov-success": "hsl(142 64% 40%)",
      "--ov-warning": "hsl(45 93% 58%)",
      "--ov-warning-soft": "hsl(32 85% 22%)",
      ...SHARED,
    },
  };

export const TOKEN_NAMES = Object.keys(TOKENS.light);
