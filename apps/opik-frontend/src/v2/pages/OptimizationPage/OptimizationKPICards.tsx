import React, { useMemo, useState, useEffect } from "react";
import { Coins } from "lucide-react";
import dayjs from "dayjs";

import {
  KPICard,
  MetricKPICard,
  getMetricKPICardConfigs,
} from "@/v2/pages-shared/experiments/KPICard/KPICard";
import { StatCard } from "@/ui/stat-card";
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
  getCompletedRunDurationSeconds,
  getEmptyRunKPICaption,
} from "./optimizationOverviewHelpers";

type MetricValue = number | undefined;

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
   * Heuristic flag: the run COMPLETED but scored nothing usable (OPIK-7029). When
   * set, the score card shows a caption so a degenerate run isn't a bare 0%/-.
   */
  scoringFailed?: boolean;
  /**
   * Exact scoring-health counts from the backend (OPIK-7159 Wave 2). When
   * present and `total_count > 0`, the score card caption shows the exact
   * failed/total numbers. When absent, falls back to the Wave-1 heuristic copy.
   * Only used when `scoringFailed` is true.
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
  scoringFailed,
  scoringHealth,
}) => {
  const kpiData = useMemo(
    () => ({
      totalOptCost: experiments.reduce(
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
    ],
  );

  const configs = getMetricKPICardConfigs({ isTestSuite, objectiveName });

  return (
    <div className="grid grid-cols-4 gap-4">
      {configs.map((config) => {
        const field = CANDIDATE_KEY_MAP[config.key];
        // Caption the score card when the run scored nothing usable, so the
        // 0%/- reads as "scoring failed" rather than a genuine result.
        // When scoringHealth is present, show exact counts; otherwise fall back
        // to the Wave-1 heuristic copy.
        const caption =
          config.key === "score"
            ? getEmptyRunKPICaption(!!scoringFailed, scoringHealth) ?? undefined
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
        <StatCard.Value
          className={kpiData.totalOptCost > 0 ? "" : "text-muted-slate"}
        >
          {kpiData.totalOptCost > 0
            ? formatAsCurrency(kpiData.totalOptCost)
            : "-"}
        </StatCard.Value>
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
