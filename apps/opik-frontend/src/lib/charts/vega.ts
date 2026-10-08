import cloneDeep from "lodash/cloneDeep";
import uniq from "lodash/uniq";
import type { Loader } from "vega";
import { ChartTokens, readChartTokens } from "./chartTokens";
import { assignSeriesColors } from "./seriesColors";

export type VegaRows = Record<string, unknown>[];
export type VegaSpec = Record<string, unknown>;

export interface VegaChartInput {
  spec: VegaSpec;
  rows?: VegaRows | null;
}

export interface VegaRenderOptions {
  // The user's colour for a series label (workspace colour map), if any; the chart rules colour the rest.
  colorOverride?: (label: string) => string | undefined;
  // "container" fills the element's height; a number fixes it.
  height?: number | "container";
}

export type RenderVegaChart = (
  el: HTMLElement,
  input: VegaChartInput,
  options?: VegaRenderOptions,
) => Promise<() => void>;

// Name of the dataset a saved spec references; rows are injected at render time.
export const VEGA_ROWS_DATASET = "rows";

const DEFAULT_HEIGHT = 220;

// Lines, points and rules are thin marks: they take the -strong palette variants.
const THIN_MARKS = new Set([
  "line",
  "point",
  "circle",
  "square",
  "rule",
  "tick",
  "trail",
]);
const DISCRETE = new Set(["nominal", "ordinal"]);

/**
 * The Vega-Lite config for a chart widget, every value a chart widget token (src/styles/widget-tokens). Read from
 * the live stylesheet, so it follows the active theme: rebuild it when the theme changes.
 */
export const buildOpikVegaConfig = (
  tokens: ChartTokens = readChartTokens(),
): VegaSpec => {
  const { type, axis, stroke, opacity, radius, legend, bar, margin, status } =
    tokens;
  const axisStyle = {
    domain: false,
    ticks: false,
    grid: false,
    labelColor: tokens.tick,
    labelFont: tokens.font,
    labelFontSize: axis.fontSize,
    labelFontWeight: axis.fontWeight,
    labelPadding: tokens.plotPaddingSm,
    labelLimit: axis.max,
    labelOverlap: true,
    titleColor: tokens.tick,
    titleFont: tokens.font,
    titleFontSize: type.xs,
    titleFontWeight: type.weightRegular,
    titlePadding: tokens.plotPaddingSm,
  };

  return {
    background: "transparent",
    font: tokens.font,
    padding: margin,
    autosize: { type: "fit", contains: "padding" },
    view: { stroke: null },
    axis: axisStyle,
    axisX: { labelAngle: 0 },
    axisY: {
      grid: true,
      gridColor: tokens.grid,
      gridWidth: stroke.thin,
      tickCount: 5,
    },
    // Value-from-zero charts (bars) draw their baseline on the category axis.
    axisBand: {
      domain: true,
      domainColor: tokens.baseline,
      domainWidth: stroke.thin,
    },
    legend: {
      orient: "bottom",
      direction: "horizontal",
      symbolType: "circle",
      symbolSize: legend.swatch * legend.swatch,
      symbolStrokeWidth: 0,
      // Swatches show the series colour, not a translucent area fill.
      symbolOpacity: 1,
      columns: 3,
      columnPadding: legend.gapX,
      rowPadding: legend.gapY,
      labelLimit: axis.max,
      labelColor: tokens.tooltip.muted,
      labelFont: tokens.font,
      labelFontSize: legend.fontSize,
      titleColor: tokens.tick,
      titleFont: tokens.font,
      titleFontSize: type.xs,
      titleFontWeight: type.weightRegular,
    },
    range: {
      category: tokens.palette,
      ordinal: tokens.palette,
      // The token set has no sequential scale yet: from the neutral tint to the primary series colour.
      ramp: [status.neutralTint, tokens.palette[9]],
      heatmap: [status.neutralTint, tokens.palette[9]],
      diverging: [
        status.bad,
        status.badTint,
        status.neutralTint,
        status.goodTint,
        status.good,
      ],
      // Meaning, never series: encode with "scale": {"domain": [...good, warning, bad], "range": "status"}.
      status: [status.good, status.warning, status.bad],
    },
    scale: {
      bandPaddingInner: bar.categoryGap,
      offsetBandPaddingInner: bar.categoryGap,
    },
    mark: { color: tokens.palette[0], tooltip: { content: "encoding" } },
    bar: { cornerRadiusEnd: radius.mark, binSpacing: bar.histogramGap },
    line: {
      color: tokens.paletteStrong[0],
      strokeWidth: stroke.default,
      interpolate: "linear",
    },
    trail: { color: tokens.paletteStrong[0] },
    point: {
      color: tokens.paletteStrong[0],
      filled: true,
      opacity: opacity.strong,
      size: Math.PI * tokens.markerSm * tokens.markerSm,
    },
    area: { opacity: opacity.subtle, line: { strokeWidth: stroke.default } },
    arc: { stroke: tokens.surface, strokeWidth: stroke.thin },
    rect: {
      stroke: tokens.surface,
      strokeWidth: tokens.heatmapCellGap,
      cornerRadius: radius.sm,
    },
    rule: {
      color: status.neutral,
      strokeWidth: stroke.thin,
      strokeDash: tokens.dashReference,
    },
    text: { color: tokens.foreground, font: tokens.font, fontSize: type.md },
    title: {
      color: tokens.foreground,
      font: tokens.font,
      fontSize: type.md,
      fontWeight: type.weightMedium,
    },
  };
};

type Unit = Record<string, unknown>;
type Channel = Record<string, unknown> | undefined;

const markType = (unit: Unit): string | undefined => {
  const mark = unit.mark;
  if (typeof mark === "string") return mark;
  if (mark && typeof mark === "object") return (mark as Unit).type as string;
  return undefined;
};

const setMarkProperty = (unit: Unit, key: string, value: unknown) => {
  const mark: Unit =
    typeof unit.mark === "string"
      ? { type: unit.mark }
      : { ...(unit.mark as Unit) };
  if (mark[key] === undefined) mark[key] = value;
  unit.mark = mark;
};

const setScaleProperty = (channel: Channel, key: string, value: unknown) => {
  if (!channel) return;
  const scale = { ...((channel.scale as Unit) ?? {}) };
  if (scale[key] === undefined) scale[key] = value;
  channel.scale = scale;
};

const TITLED_CHANNELS = ["x", "y", "color", "theta", "size", "opacity"];

// "experiment_count" → "Experiment count", "dataset_name" → "Dataset": a readable default for a field-named title.
const humanizeField = (field: string) => {
  const words = field
    .replace(/_name$/, "")
    .replace(/[_.]+/g, " ")
    .replace(/([a-z])([A-Z])/g, "$1 $2")
    .trim()
    .toLowerCase();
  return words.charAt(0).toUpperCase() + words.slice(1);
};

const setChannelProperty = (
  channel: Channel,
  part: "axis" | "legend",
  key: string,
  value: unknown,
) => {
  if (!channel || channel[part] === null) return;
  const props = { ...((channel[part] as Unit) ?? {}) };
  if (props[key] === undefined) props[key] = value;
  channel[part] = props;
};

const distinctCount = (rows: VegaRows, field: unknown) =>
  typeof field === "string" ? new Set(rows.map((row) => row[field])).size : 0;

/** Colour and shape rules a config cannot express, because they depend on the mark and the data. */
const applyChartRules = (
  unit: Unit,
  rows: VegaRows,
  tokens: ChartTokens,
  colorOverride?: (label: string) => string | undefined,
) => {
  const encoding = unit.encoding as Record<string, Channel> | undefined;
  const mark = markType(unit);
  if (!encoding || !mark) return;
  const { color, x, y, xOffset } = encoding;

  TITLED_CHANNELS.forEach((name) => {
    const channel = encoding[name];
    if (
      channel &&
      typeof channel.field === "string" &&
      channel.title === undefined
    ) {
      channel.title = humanizeField(channel.field);
    }
  });

  // Category labels share the plot width instead of the value axis's fixed --chart-axis-max, and follow resizes.
  const categories = distinctCount(rows, x?.field);
  if (x && DISCRETE.has(x.type as string) && categories) {
    setChannelProperty(x, "axis", "labelLimit", {
      expr: `max(${tokens.axis.max}, width / ${categories} - ${tokens.plotPaddingSm})`,
    });
  }
  if (color) {
    setChannelProperty(color, "legend", "labelLimit", {
      expr: `max(${tokens.axis.max}, width / 3 - ${tokens.legend.gapX * 2})`,
    });
  }

  if (
    color &&
    typeof color.field === "string" &&
    !color.scale &&
    (color.type === undefined || DISCRETE.has(color.type as string))
  ) {
    const field = color.field;
    const domain = uniq(
      rows
        .map((row) => row[field])
        .filter((value) => value !== null && value !== undefined)
        .map(String),
    );
    if (domain.length) {
      color.scale = {
        domain,
        range: assignSeriesColors(domain, tokens, {
          field,
          strong: THIN_MARKS.has(mark),
          override: colorOverride,
        }),
      };
    }
  }

  if (mark === "area" && color)
    setMarkProperty(unit, "opacity", tokens.opacity.soft);

  if (mark === "bar") {
    const horizontal =
      y && DISCRETE.has(y.type as string) && !DISCRETE.has(x?.type as string);
    const stacked = Boolean(color) && !xOffset;
    if (horizontal)
      setScaleProperty(y, "paddingInner", tokens.bar.categoryGapLoose);
    else if (stacked)
      setScaleProperty(x, "paddingInner", tokens.bar.categoryGapLoose);
  }
};

const NUMERIC = /^-?\d+(\.\d+)?([eE][-+]?\d+)?$/;

// toJSONString(map(...)) returns every value as a string; Vega needs numbers to scale and aggregate them.
export const coerceNumericRows = (rows: VegaRows): VegaRows =>
  rows.map((row) => {
    const result: Record<string, unknown> = {};
    for (const [key, value] of Object.entries(row)) {
      result[key] =
        typeof value === "string" && NUMERIC.test(value)
          ? Number(value)
          : value;
    }
    return result;
  });

export const prepareVegaSpec = (
  { spec, rows }: VegaChartInput,
  { colorOverride, height = DEFAULT_HEIGHT }: VegaRenderOptions,
  tokens: ChartTokens = readChartTokens(),
): VegaSpec => {
  const prepared = cloneDeep(spec);
  // Styling and size belong to the renderer, never to the stored spec.
  delete prepared.$schema;
  delete prepared.config;
  // vega-embed merges usermeta.embedOptions over ours, which would let a stored spec turn ast off or patch itself.
  delete prepared.usermeta;
  prepared.width = "container";
  prepared.height = height;

  const data = prepared.data as Record<string, unknown> | undefined;
  const values = rows
    ? coerceNumericRows(rows)
    : (data?.values as VegaRows | undefined) ?? [];
  if (rows && typeof data?.name === "string") {
    prepared.datasets = {
      ...((prepared.datasets as object) ?? {}),
      [data.name]: values,
    };
  }

  applyChartRules(prepared, values, tokens, colorOverride);
  if (Array.isArray(prepared.layer)) {
    prepared.layer.forEach((layer: Unit) =>
      applyChartRules(layer, values, tokens, colorOverride),
    );
  }

  return prepared;
};

const escapeHtml = (value: unknown) =>
  String(value ?? "")
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;");

const formatTooltipValue = (value: unknown) =>
  typeof value === "number"
    ? value.toLocaleString(undefined, { maximumFractionDigits: 4 })
    : String(value ?? "");

/**
 * A tooltip built from the tooltip tokens. vega-tooltip is not used because it appends to the global `document`,
 * which is the wrong document when the host renders into the Ollie console iframe; this one lives in the chart
 * element's own document, with inline styles because that document has none of Opik's CSS.
 */
const createTooltipHandler = (el: HTMLElement, tokens: ChartTokens) => {
  const doc = el.ownerDocument;
  const { tooltip: t, legend, font, type } = tokens;
  let tooltip: HTMLDivElement | null = null;

  const handler = (
    _handler: unknown,
    event: MouseEvent,
    item: { fill?: string; stroke?: string } | null,
    value: unknown,
  ) => {
    if (value === null || value === undefined || value === "") {
      if (tooltip) tooltip.style.display = "none";
      return;
    }

    if (!tooltip) {
      tooltip = doc.createElement("div");
      Object.assign(tooltip.style, {
        position: "fixed",
        zIndex: "9999",
        pointerEvents: "none",
        minWidth: `${t.minWidth}px`,
        maxWidth: "288px",
        padding: `${t.paddingY}px ${t.paddingX}px`,
        borderRadius: `${t.radius}px`,
        border: `1px solid ${t.border}`,
        background: t.background,
        color: t.foreground,
        boxShadow: t.shadow,
        font: `${type.weightRegular} ${t.fontSize}px/1.33 ${font}`,
      });
      doc.body.appendChild(tooltip);
    }

    const color = item?.fill || item?.stroke || "";
    const swatch = legend.swatch;
    const entries =
      typeof value === "object"
        ? Object.entries(value as Record<string, unknown>)
        : [["", value] as [string, unknown]];
    tooltip.innerHTML = entries
      .map(
        ([key, entryValue], index) =>
          `<div style="display:flex;align-items:center;gap:8px;justify-content:space-between;padding:2px 0">` +
          `<span style="display:flex;align-items:center;gap:6px;color:${t.muted};opacity:${t.secondaryOpacity};overflow:hidden;text-overflow:ellipsis;white-space:nowrap">` +
          (index === 0 && color && color !== "transparent"
            ? `<span style="width:${swatch}px;height:${swatch}px;border-radius:9999px;flex:none;background:${escapeHtml(
                color,
              )}"></span>`
            : "") +
          `${escapeHtml(key)}</span>` +
          `<span style="font-weight:${type.weightMedium}">${escapeHtml(
            formatTooltipValue(entryValue),
          )}</span></div>`,
      )
      .join("");

    const view = doc.defaultView ?? window;
    tooltip.style.display = "block";
    const { offsetWidth, offsetHeight } = tooltip;
    const left = Math.min(
      event.clientX + 12,
      view.innerWidth - offsetWidth - 8,
    );
    const top =
      event.clientY + 12 + offsetHeight > view.innerHeight
        ? event.clientY - offsetHeight - 12
        : event.clientY + 12;
    tooltip.style.left = `${Math.max(8, left)}px`;
    tooltip.style.top = `${Math.max(8, top)}px`;
  };

  const dispose = () => {
    tooltip?.remove();
    tooltip = null;
  };

  return { handler, dispose };
};

// Rows are injected inline, so a chart needs no resource of its own. Every URL a spec names (data.url, an image mark,
// an href) passes through sanitize; refusing it stops a stored spec sending rows, or same-origin API reads made with
// the viewer's session, anywhere.
export const withoutNetwork = (vegaLoader: Loader): Loader => {
  vegaLoader.sanitize = () =>
    Promise.reject(new Error("Charts cannot load external resources"));
  return vegaLoader;
};

export const renderVegaChart: RenderVegaChart = async (
  el,
  input,
  options = {},
) => {
  const [{ default: embed }, { expressionInterpreter }, { loader }] =
    await Promise.all([
      import("vega-embed"),
      import("vega-interpreter"),
      import("vega"),
    ]);
  const tokens = readChartTokens();
  const tooltip = createTooltipHandler(el, tokens);
  const fillHeight = options.height === "container";
  const size = () => ({
    width: Math.max(el.clientWidth, 120),
    height: Math.max(el.clientHeight, 120),
  });

  // Explicit sizes kept in step by a ResizeObserver: Vega's "container" sizing listens to the host window, which
  // never resizes when the console iframe or a dashboard widget does.
  const spec = prepareVegaSpec(input, options, tokens);
  spec.width = size().width;
  if (fillHeight) spec.height = size().height;

  const result = await embed(el, spec as never, {
    actions: false,
    renderer: "svg",
    loader: withoutNetwork(loader()),
    config: buildOpikVegaConfig(tokens) as never,
    // Model-written expressions are interpreted, never compiled with new Function().
    ast: true,
    expr: expressionInterpreter,
    tooltip: tooltip.handler as never,
  });

  const view = result.view;
  const Observer =
    (el.ownerDocument.defaultView as (Window & typeof globalThis) | null)
      ?.ResizeObserver ?? ResizeObserver;
  const observer = new Observer(() => {
    const next = size();
    if (
      next.width === view.width() &&
      (!fillHeight || next.height === view.height())
    )
      return;
    view.width(next.width);
    if (fillHeight) view.height(next.height);
    view.runAsync();
  });
  observer.observe(el);

  return () => {
    observer.disconnect();
    result.finalize();
    tooltip.dispose();
  };
};
