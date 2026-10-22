import React from "react";
import { cn } from "@/lib/utils";

type Rows = Record<string, unknown>[];

// How Ollie's KPI value is formatted; the console's chart card uses the same rules, so a KPI reads the same in chat.
export type MetricFormat =
  | "cost_total"
  | "cost_item"
  | "rate"
  | "score"
  | "latency"
  | "count";

const METRIC_FORMATS: MetricFormat[] = [
  "cost_total",
  "cost_item",
  "rate",
  "score",
  "latency",
  "count",
];

// `{"kpi": {...}}` in place of a Vega-Lite spec: one number from the rows' `value`.
export type KpiSpec = {
  value: string;
  period?: string | null;
  aggregate: "sum" | "avg" | "last";
  format: MetricFormat;
  better?: "higher" | "lower" | null;
  // Set when the query follows the date range, so the value is compared with the window before.
  window_days?: number | null;
};

export const parseKpiSpec = (
  spec: Record<string, unknown> | undefined,
): KpiSpec | null => {
  const kpi = spec?.kpi as Partial<KpiSpec> | undefined;
  return kpi &&
    typeof kpi.value === "string" &&
    METRIC_FORMATS.includes(kpi.format as MetricFormat)
    ? (kpi as KpiSpec)
    : null;
};

const significant = (value: number, digits: number) =>
  String(Number(value.toPrecision(digits)));

const compact = (value: number, digits: number) => {
  const abs = Math.abs(value);
  if (abs >= 1e9) return `${significant(value / 1e9, digits)}B`;
  if (abs >= 1e6) return `${significant(value / 1e6, digits)}M`;
  if (abs >= 1e3) return `${significant(value / 1e3, digits)}k`;
  return significant(value, digits);
};

export const formatMetric = (value: number, format: MetricFormat): string => {
  if (!Number.isFinite(value)) return "—";
  const sign = value < 0 ? "-" : "";
  const abs = Math.abs(value);
  switch (format) {
    case "cost_total":
      // Never rounded to zero: a tiny cost still shows that something was spent.
      if (abs > 0 && abs < 0.01) return `${sign}<$0.01`;
      return `${sign}$${compact(abs, 3)}`;
    case "cost_item":
      if (abs > 0 && abs < 0.0001) return `${sign}<$0.0001`;
      return `${sign}$${Number(abs.toFixed(4))}`;
    case "rate": {
      const percent = abs * 100;
      if (percent > 0 && percent < 0.1) return `${sign}<0.1%`;
      return `${sign}${percent.toFixed(1)}%`;
    }
    case "score":
      return value.toFixed(3);
    case "latency":
      return abs < 1000
        ? `${sign}${Math.round(abs)} ms`
        : `${sign}${(abs / 1000).toFixed(2)} s`;
    case "count":
      return abs >= 10_000
        ? `${sign}${compact(abs, 2)}`
        : value.toLocaleString("en-US", { maximumFractionDigits: 2 });
  }
};

const numbers = (rows: Rows, field: string) =>
  rows
    .map((row) => Number(row[field]))
    .filter((value) => Number.isFinite(value));

const aggregate = (rows: Rows, kpi: KpiSpec): number | null => {
  const values = numbers(rows, kpi.value);
  if (!values.length) return null;
  if (kpi.aggregate === "last") return values[values.length - 1];
  const sum = values.reduce((a, b) => a + b, 0);
  return kpi.aggregate === "avg" ? sum / values.length : sum;
};

const Sparkline: React.FC<{ values: number[] }> = ({ values }) => {
  const min = Math.min(...values);
  const range = Math.max(...values) - min || 1;
  const points = values
    .map(
      (v, i) =>
        `${(i / (values.length - 1)) * 100},${28 - ((v - min) / range) * 24}`,
    )
    .join(" ");
  return (
    <svg
      viewBox="0 0 100 30"
      preserveAspectRatio="none"
      className="h-6 w-full max-w-48"
      aria-hidden
    >
      <polyline
        points={points}
        fill="none"
        stroke="var(--chart-purple, #945fcf)"
        strokeWidth={1.5}
        vectorEffect="non-scaling-stroke"
      />
    </svg>
  );
};

type OllieKpiProps = {
  kpi: KpiSpec;
  rows: Rows;
  previousRows?: Rows | null;
  // "previous 30 days": the window the comparison runs over.
  comparedWith?: string;
};

const OllieKpi: React.FC<OllieKpiProps> = ({
  kpi,
  rows,
  previousRows,
  comparedWith,
}) => {
  const value = aggregate(rows, kpi);
  const previous = previousRows ? aggregate(previousRows, kpi) : null;
  const change =
    value !== null && previous ? (value - previous) / Math.abs(previous) : null;
  const good =
    change === null || !kpi.better || change === 0
      ? null
      : change > 0 === (kpi.better === "higher");
  const series = kpi.period ? numbers(rows, kpi.value) : [];
  return (
    <div className="flex size-full flex-col justify-center gap-1 px-4">
      <span className="text-3xl font-semibold tabular-nums text-foreground">
        {value === null ? "—" : formatMetric(value, kpi.format)}
      </span>
      {change !== null && comparedWith && (
        <span className="comet-body-xs text-light-slate">
          <span
            className={cn(
              good === null && "text-muted-slate",
              good === true && "text-chart-green",
              good === false && "text-destructive",
            )}
          >
            {change >= 0 ? "▲" : "▼"} {Math.abs(change * 100).toFixed(1)}%
          </span>{" "}
          vs {comparedWith}
        </span>
      )}
      {series.length > 1 && <Sparkline values={series} />}
    </div>
  );
};

export default OllieKpi;
