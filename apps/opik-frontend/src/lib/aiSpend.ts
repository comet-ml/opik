import { calcFormatterAwarePercentage } from "@/lib/percentage";

const AI_SPEND_ROUTE_SEGMENT = "/ai-spend";

export const isAiSpendRoute = (pathname: string): boolean => {
  const normalized = pathname.replace(/\/+$/, "");
  return (
    normalized.endsWith(AI_SPEND_ROUTE_SEGMENT) ||
    normalized.includes(`${AI_SPEND_ROUTE_SEGMENT}/`)
  );
};

export type SpendWindow = 7 | 30 | 90;
export const SPEND_WINDOWS: SpendWindow[] = [7, 30, 90];

export const AI_SPEND_PROJECT_NAME = "claude-code";
export const NO_DATA = "N/A";

export type SpendMetric = { current: number | null; previous: number | null };

export const getSpendInterval = (windowDays: SpendWindow) => {
  const end = new Date();
  const start = new Date(end.getTime() - windowDays * 24 * 60 * 60 * 1000);
  return {
    intervalStart: start.toISOString(),
    intervalEnd: end.toISOString(),
  };
};

export const getSpendTrendPercentage = (
  metric: SpendMetric,
): number | undefined =>
  calcFormatterAwarePercentage(
    metric.current ?? undefined,
    metric.previous ?? undefined,
  );

export const formatSpendUsd = (value: number | null): string =>
  value === null
    ? NO_DATA
    : `$${value.toLocaleString("en-US", {
        minimumFractionDigits: 2,
        maximumFractionDigits: 2,
      })}`;

export const formatSpendCount = (value: number | null): string =>
  value === null ? NO_DATA : value.toLocaleString("en-US");
