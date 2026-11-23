export type Theme = "light" | "dark";

const FONT_SANS =
  'Inter, ui-sans-serif, system-ui, -apple-system, "Segoe UI", sans-serif';
const FONT_MONO =
  '"Ubuntu Mono", ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, "Liberation Mono", "Courier New", monospace';

const PRIMITIVES: Record<string, string> = {
  "base-white": "#ffffff",
  "neutral-50": "#fafafa",
  "neutral-100": "#f4f4f5",
  "neutral-150": "#efeff1",
  "neutral-200": "#e4e4e7",
  "neutral-300": "#d4d4d8",
  "neutral-400": "#a1a1aa",
  "neutral-500": "#71717a",
  "neutral-600": "#52525b",
  "neutral-700": "#3f3f46",
  "neutral-800": "#27272a",
  "neutral-900": "#18181b",
  "neutral-950": "#09090b",
  "primary-400": "#6487fc",
  "primary-500": "#5155f5",
  "primary-600": "#3f43e0",
  "red-300": "#ff6467",
  "red-400": "#fd4c50",
  "red-500": "#fb2c36",
  "red-700": "#dc2626",
  "green-300": "#4df89a",
  "green-400": "#05df72",
  "green-500": "#00c950",
  "green-700": "#11834b",
  "amber-300": "#ffbb29",
  "amber-400": "#ffab14",
  "amber-500": "#fe9a00",
  "amber-700": "#bb4d00",
  "lime-300": "#bbf451",
  "lime-400": "#9ae600",
  "yellow-300": "#ffdf20",
  "yellow-400": "#fdc700",
  "teal-300": "#46ecd5",
  "teal-400": "#00d5be",
  "cyan-300": "#53eafd",
  "cyan-400": "#00d3f2",
  "violet-300": "#c4b4ff",
  "violet-400": "#a684ff",
  "fuchsia-300": "#f4a8ff",
  "fuchsia-400": "#ed6aff",
  "pink-300": "#ff90cd",
  "pink-400": "#fb64b6",
};

const ACCENTS = ["lime", "yellow", "teal", "cyan", "violet", "fuchsia", "pink"];
const CHART_SERIES = [
  "violet",
  "cyan",
  "fuchsia",
  "lime",
  "yellow",
  "teal",
  "pink",
];
const STATUSES = { error: "red", success: "green", warning: "amber" };

const SEMANTIC: Record<string, [light: string, dark: string]> = {
  "background-primary": ["base-white", "neutral-950"],
  "background-primary-hover": ["neutral-50", "neutral-900"],
  "background-secondary": ["neutral-100", "neutral-800"],
  "background-secondary-hover": ["neutral-200", "neutral-700"],
  "background-subtle": ["neutral-50", "neutral-900"],
  "text-primary": ["neutral-800", "neutral-100"],
  "text-secondary": ["neutral-500", "neutral-400"],
  "text-placeholder": ["neutral-500", "neutral-400"],
  "text-disabled": ["neutral-400", "neutral-600"],
  "text-brand": ["primary-500", "primary-400"],
  "text-on-action": ["neutral-100", "neutral-100"],
  "icon-primary": ["neutral-800", "neutral-200"],
  "icon-secondary": ["neutral-500", "neutral-400"],
  "icon-brand": ["primary-500", "primary-400"],
  "icon-on-action": ["neutral-100", "neutral-100"],
  "icon-on-color": ["neutral-800", "neutral-800"],
  "border-default": ["neutral-150", "neutral-800"],
  "border-strong": ["neutral-500", "neutral-500"],
  "border-focus": ["primary-500", "primary-400"],
  "action-primary": ["primary-500", "primary-500"],
  "action-primary-hover": ["primary-600", "primary-400"],
  ...Object.fromEntries(
    ACCENTS.flatMap((hue) => [
      [`accent-${hue}-tile`, [`${hue}-300`, `${hue}-400`]],
      [`accent-${hue}-mark`, [`${hue}-400`, `${hue}-300`]],
    ]),
  ),
  ...Object.fromEntries(
    CHART_SERIES.map((hue, i) => [
      `chart-series-${i + 1}`,
      [`${hue}-400`, `${hue}-400`],
    ]),
  ),
  ...Object.fromEntries(
    Object.entries(STATUSES).map(([status, hue]) => [
      `chart-${status}`,
      [`${hue}-400`, `${hue}-400`],
    ]),
  ),
  "chart-grid": ["neutral-150", "neutral-800"],
  ...Object.fromEntries(
    Object.entries(STATUSES).flatMap(([status, hue]) => [
      [`status-${status}-default`, [`${hue}-500`, `${hue}-500`]],
      [`status-${status}-text`, [`${hue}-700`, `${hue}-400`]],
      [`status-${status}-tile`, [`${hue}-300`, `${hue}-400`]],
      [`status-${status}-mark`, [`${hue}-400`, `${hue}-300`]],
    ]),
  ),
};

const font = (family: string, weight: number, size: number, line: number) =>
  `${weight} ${size}px/${line}px var(--ov-font-${family})`;

const SHARED = {
  "--ov-font-sans": FONT_SANS,
  "--ov-font-mono": FONT_MONO,
  "--ov-type-heading-1": font("sans", 600, 20, 28),
  "--ov-type-heading-2": font("sans", 600, 16, 24),
  "--ov-type-heading-3": font("sans", 600, 14, 20),
  "--ov-type-body-medium": font("sans", 400, 14, 20),
  "--ov-type-body-medium-strong": font("sans", 500, 14, 20),
  "--ov-type-body-small": font("sans", 400, 12, 16),
  "--ov-type-body-small-strong": font("sans", 500, 12, 16),
  "--ov-type-caption": font("sans", 400, 11, 14),
  "--ov-type-overline": font("sans", 500, 10, 16),
  "--ov-type-mono-medium": font("mono", 400, 14, 20),
  "--ov-type-mono-medium-strong": font("mono", 500, 14, 20),
  "--ov-type-mono-small": font("mono", 400, 12, 16),
  "--ov-type-mono-small-strong": font("mono", 500, 12, 16),
  "--ov-type-mono-caption": font("mono", 400, 11, 14),
  "--ov-type-mono-caption-strong": font("mono", 500, 11, 14),
  ...Object.fromEntries(
    [2, 4, 6, 8, 12, 16, 24, 32].map((px) => [`--ov-spacing-${px}`, `${px}px`]),
  ),
  "--ov-radius-2": "2px",
  "--ov-radius-4": "4px",
  "--ov-radius-8": "8px",
  "--ov-radius-full": "9999px",
  "--ov-opacity-inactive": "0.8",
  "--ov-opacity-disabled": "0.6",
};

const colors = (mode: 0 | 1) =>
  Object.fromEntries(
    Object.entries(SEMANTIC).map(([name, pair]) => [
      `--ov-${name}`,
      PRIMITIVES[pair[mode]],
    ]),
  );

export const TOKENS: Readonly<Record<Theme, Readonly<Record<string, string>>>> =
  {
    light: { ...colors(0), ...SHARED },
    dark: { ...colors(1), ...SHARED },
  };

export const TOKEN_NAMES = Object.keys(TOKENS.light);
