import resolvedTokens from "@/styles/widget-tokens/widget-tokens.resolved.json";

/**
 * Reads the chart widget tokens (src/styles/widget-tokens) into the values a renderer needs. Live CSS wins; the
 * resolved export is the fallback for a token the document cannot resolve. Read again whenever the theme changes.
 */

type ThemeMode = "light" | "dark";
type ResolvedToken = { light: string | number; dark: string | number };

const RESOLVED = resolvedTokens.tokens as Record<string, ResolvedToken>;

export const SERIES_COUNT = 10;

export interface ChartTokens {
  font: string;
  palette: string[];
  paletteStrong: string[];
  other: string;
  otherStrong: string;
  status: {
    good: string;
    warning: string;
    bad: string;
    neutral: string;
    goodTint: string;
    badTint: string;
    neutralTint: string;
  };
  surface: string;
  foreground: string;
  tick: string;
  grid: string;
  baseline: string;
  type: {
    xs: number;
    md: number;
    weightRegular: number;
    weightMedium: number;
  };
  axis: { fontSize: number; fontWeight: number; max: number };
  stroke: { thin: number; default: number };
  dashReference: number[];
  markerSm: number;
  opacity: { subtle: number; soft: number; strong: number };
  radius: { sm: number; mark: number };
  heatmapCellGap: number;
  bar: { categoryGap: number; categoryGapLoose: number; histogramGap: number };
  plotPaddingSm: number;
  margin: { top: number; right: number; bottom: number; left: number };
  legend: { swatch: number; gapX: number; gapY: number; fontSize: number };
  tooltip: {
    background: string;
    foreground: string;
    muted: string;
    border: string;
    shadow: string;
    radius: number;
    paddingX: number;
    paddingY: number;
    minWidth: number;
    fontSize: number;
    secondaryOpacity: number;
  };
}

const themeMode = (): ThemeMode => {
  const root = document.documentElement;
  return root.dataset.theme === "dark" || root.classList.contains("dark")
    ? "dark"
    : "light";
};

let colorContext: CanvasRenderingContext2D | null = null;

// Any CSS colour (hex, `hsl(215 16% 47%)`, `var()`-free strings) as a value Vega and inline styles accept.
const normalizeColor = (value: string): string => {
  colorContext ??= document.createElement("canvas").getContext("2d");
  if (!colorContext || !value) return value;
  colorContext.fillStyle = "#000";
  colorContext.fillStyle = value;
  return colorContext.fillStyle;
};

const rootFontSize = () =>
  parseFloat(getComputedStyle(document.documentElement).fontSize) || 16;

// "10px", "0.75rem", "20%" (→ 0.2), "1.5" → a number Vega takes.
const toNumber = (value: string): number => {
  const trimmed = value.trim();
  const number = parseFloat(trimmed);
  if (Number.isNaN(number)) return 0;
  if (trimmed.endsWith("rem")) return number * rootFontSize();
  if (trimmed.endsWith("%")) return number / 100;
  return number;
};

/** Resolves a colour the palette or a workspace map hands out as `var(--x)` against the live document. */
export const resolveCssColor = (color: string): string => {
  const match = /^var\((--[\w-]+)\)$/.exec(color.trim());
  if (!match) return normalizeColor(color);
  const styles = getComputedStyle(document.documentElement);
  const raw =
    styles.getPropertyValue(match[1]).trim() ||
    String(RESOLVED[match[1]]?.[themeMode()] ?? "");
  return normalizeColor(raw);
};

export const readChartTokens = (): ChartTokens => {
  const styles = getComputedStyle(document.documentElement);
  const mode = themeMode();
  const raw = (name: string) =>
    styles.getPropertyValue(name).trim() ||
    String(RESOLVED[name]?.[mode] ?? "");
  const color = (name: string) => normalizeColor(raw(name));
  const number = (name: string) => toNumber(raw(name));
  // Opik's primitives are bare HSL channels.
  const hsl = (name: string) => {
    const value = raw(name);
    return normalizeColor(value.startsWith("#") ? value : `hsl(${value})`);
  };

  const series = Array.from({ length: SERIES_COUNT }, (_, i) => i + 1);

  return {
    font:
      getComputedStyle(document.body).fontFamily ||
      "Inter, ui-sans-serif, system-ui, sans-serif",
    palette: series.map((i) => color(`--chart-${i}`)),
    paletteStrong: series.map((i) => color(`--chart-${i}-strong`)),
    other: color("--chart-other"),
    otherStrong: color("--chart-other-strong"),
    status: {
      good: color("--chart-good"),
      warning: color("--chart-warning"),
      bad: color("--chart-bad"),
      neutral: color("--chart-neutral"),
      goodTint: color("--chart-good-tint"),
      badTint: color("--chart-bad-tint"),
      neutralTint: color("--chart-neutral-tint"),
    },
    surface: hsl("--background"),
    foreground: hsl("--foreground"),
    tick: color("--chart-tick-stroke"),
    grid: color("--chart-grid"),
    baseline: color("--chart-baseline"),
    type: {
      xs: number("--chart-font-size-xs"),
      md: number("--chart-font-size-md"),
      weightRegular: number("--chart-font-weight-regular"),
      weightMedium: number("--chart-font-weight-medium"),
    },
    axis: {
      fontSize: number("--chart-axis-font-size"),
      fontWeight: number("--chart-axis-font-weight"),
      max: number("--chart-axis-max"),
    },
    stroke: {
      thin: number("--chart-stroke-thin"),
      default: number("--chart-stroke-default"),
    },
    dashReference: raw("--chart-dash-reference")
      .split(/\s+/)
      .map(Number)
      .filter((n) => !Number.isNaN(n)),
    markerSm: number("--chart-marker-sm"),
    opacity: {
      subtle: number("--chart-opacity-subtle"),
      soft: number("--chart-opacity-soft"),
      strong: number("--chart-opacity-strong"),
    },
    radius: {
      sm: number("--chart-radius-sm"),
      mark: number("--chart-radius-mark"),
    },
    heatmapCellGap: number("--chart-heatmap-cell-gap"),
    bar: {
      categoryGap: number("--chart-bar-category-gap"),
      categoryGapLoose: number("--chart-bar-category-gap-loose"),
      histogramGap: number("--chart-bar-category-gap-none"),
    },
    plotPaddingSm: number("--chart-plot-padding-sm"),
    margin: {
      top: number("--chart-margin-top"),
      right: number("--chart-margin-right"),
      bottom: number("--chart-margin-bottom"),
      left: number("--chart-margin-left"),
    },
    legend: {
      swatch: number("--chart-legend-swatch"),
      gapX: number("--chart-legend-gap-x"),
      gapY: number("--chart-legend-gap-y"),
      fontSize: number("--chart-legend-font-size"),
    },
    tooltip: {
      background: color("--chart-tooltip-bg"),
      foreground: color("--chart-tooltip-fg"),
      muted: color("--chart-tooltip-muted"),
      border: color("--chart-tooltip-border"),
      shadow: raw("--chart-tooltip-shadow"),
      radius: number("--chart-tooltip-radius"),
      paddingX: number("--chart-tooltip-padding-x"),
      paddingY: number("--chart-tooltip-padding-y"),
      minWidth: number("--chart-tooltip-min-width"),
      fontSize: number("--chart-tooltip-font-size"),
      secondaryOpacity: number("--chart-tooltip-secondary-opacity"),
    },
  };
};
