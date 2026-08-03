import React, { useMemo, useState, useEffect } from "react";
import { Coins } from "lucide-react";
import dayjs from "dayjs";

import {
  KPICard,
  MetricKPICard,
  getMetricKPICardConfigs,
} from "@/v2/pages-shared/experiments/KPICard/KPICard";
import { StatCard } from "@/ui/stat-card";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import {
  formatAsDuration,
  formatAsCurrency,
} from "@/lib/optimization-formatters";
import { Experiment } from "@/types/datasets";
import {
  AggregatedCandidate,
  OptimizationScoringHealth,
} from "@/types/optimizations";
import {
  EMPTY_RUN_CAUSE,
  EmptyRunCause,
  getCompletedRunDurationSeconds,
  getEmptyRunKPICaption,
} from "./optimizationOverviewHelpers";

type MetricValue = number | undefined;

/**
 * Unrounded cost for the tooltip, where the card's 2-4 decimal places would hide
 * the difference this run actually made. Six significant digits keep sub-cent
 * figures intact ($0.00095615) while dropping the float noise a client-side sum
 * produces (0.1 + 0.05 = 0.15000000000000002).
 */
const exactCost = (dollars: number): string =>
  String(Number(dollars.toPrecision(6)));

const CANDIDATE_KEY_MAP: Record<string, keyof AggregatedCandidate> = {
  score: "score",
  latency: "latencyP50",
  cost: "runtimeCost",
};

type ElapsedDurationProps = {
  /** ISO timestamp the run started at. */
  startedAt: string;
};

const ElapsedDuration: React.FunctionComponent<ElapsedDurationProps> = ({
  startedAt,
}) => {
  const [now, setNow] = useState(() => dayjs());

  useEffect(() => {
    const id = setInterval(() => setNow(dayjs()), 1000);
    return () => clearInterval(id);
  }, []);

  const start = dayjs(startedAt);
  if (!start.isValid()) return null;

  // `true` keeps fractional seconds so the ticking caption reads smoothly.
  const elapsed = now.diff(start, "second", true);
  if (elapsed <= 0) return null;

  return <StatCard.Caption>{formatAsDuration(elapsed)} total</StatCard.Caption>;
};

type OptimizationKPICardsProps = {
  experiments: Experiment[];
  baselineCandidate?: AggregatedCandidate;
  bestCandidate?: AggregatedCandidate;
  isTestSuite?: boolean;
  objectiveName?: string;
  optimizationCreatedAt?: string;
  optimizationLastUpdatedAt?: string;
  isInProgress?: boolean;
  /**
   * Backend aggregate for the whole run (Optimization.total_optimization_cost).
   * Includes optimizer-internal spend (e.g. GEPA reflection calls) that belongs
   * to no trial (OPIK-7521), so it wins over the client-side trial sum.
   */
  totalOptimizationCost?: number;
  /**
   * When not `NONE`, the score card shows a caption naming the cause, so a
   * degenerate run is not a bare 0%/- (OPIK-7029, OPIK-7458).
   */
  emptyRunCause?: EmptyRunCause;
  /**
   * Exact failed/total counts from the backend (OPIK-7159 Wave 2), falling back
   * to the Wave-1 copy when absent. Only consulted for SCORING_FAILED.
   */
  scoringHealth?: OptimizationScoringHealth;
};

const OptimizationKPICards: React.FunctionComponent<
  OptimizationKPICardsProps
> = ({
  experiments,
  baselineCandidate,
  bestCandidate,
  isTestSuite,
  objectiveName,
  optimizationCreatedAt,
  optimizationLastUpdatedAt,
  isInProgress,
  emptyRunCause = EMPTY_RUN_CAUSE.NONE,
  scoringHealth,
  totalOptimizationCost,
}) => {
  const kpiData = useMemo(
    () => ({
      // The backend aggregate wins when it has a value, because it also covers
      // optimizer-internal spend that belongs to no trial, and because it spans
      // the whole run — the client-side sum below only sees the current page of
      // trials, so it under-reports any run with more trials than fit one page.
      // The aggregate comes back as 0 rather than null when there is nothing to
      // report, so treat 0 as "no answer" and fall back — otherwise a run whose
      // trials clearly cost something would render as "-".
      usesBackendTotal:
        totalOptimizationCost != null && totalOptimizationCost > 0,
      totalOptCost:
        totalOptimizationCost != null && totalOptimizationCost > 0
          ? totalOptimizationCost
          : experiments.reduce(
              (sum, e) => sum + (e.total_estimated_cost ?? 0),
              0,
            ),
      totalDuration: getCompletedRunDurationSeconds({
        isInProgress,
        optimizationCreatedAt,
        optimizationLastUpdatedAt,
        trialCreatedTimes: experiments.map((e) => e.created_at),
      }),
    }),
    [
      experiments,
      optimizationCreatedAt,
      optimizationLastUpdatedAt,
      isInProgress,
      totalOptimizationCost,
    ],
  );

  const configs = getMetricKPICardConfigs({ isTestSuite, objectiveName });

  // The value can exceed the sum of the trials in the table below, both because
  // optimizer-internal calls belong to no trial and because that table is
  // paginated. Name the source, or the card reads as an arithmetic error.
  const costTooltip = `$${exactCost(kpiData.totalOptCost)}. ${
    kpiData.usesBackendTotal
      ? "Whole-run spend, including optimizer-internal LLM calls (e.g. reflection) that belong to no trial."
      : "Summed from the trials loaded on this page."
  }`;

  return (
    <div className="grid grid-cols-4 gap-4">
      {configs.map((config) => {
        const field = CANDIDATE_KEY_MAP[config.key];
        // Caption the score card so a 0%/- is explained rather than read as a
        // real result.
        const caption =
          config.key === "score"
            ? getEmptyRunKPICaption(emptyRunCause, scoringHealth) ?? undefined
            : undefined;
        return (
          <MetricKPICard
            key={config.key}
            icon={config.icon}
            label={config.label}
            baseline={baselineCandidate?.[field] as MetricValue}
            current={bestCandidate?.[field] as MetricValue}
            formatter={config.formatter}
            trend={config.trend}
            caption={caption}
          />
        );
      })}

      <KPICard icon={Coins} label="Optimization cost">
        {kpiData.totalOptCost > 0 ? (
          <TooltipWrapper content={costTooltip}>
            <StatCard.Value>
              {formatAsCurrency(kpiData.totalOptCost)}
            </StatCard.Value>
          </TooltipWrapper>
        ) : (
          <StatCard.Value className="text-muted-slate">-</StatCard.Value>
        )}
        {isInProgress && optimizationCreatedAt ? (
          <ElapsedDuration startedAt={optimizationCreatedAt} />
        ) : (
          kpiData.totalDuration != null &&
          kpiData.totalDuration > 0 && (
            <StatCard.Caption>
              {formatAsDuration(kpiData.totalDuration)} total
            </StatCard.Caption>
          )
        )}
      </KPICard>
    </div>
  );
};

export default OptimizationKPICards;
