import React, { useMemo, useState, useEffect } from "react";
import { Coins } from "lucide-react";

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
import { AggregatedCandidate } from "@/types/optimizations";
import { getOptimizationDurationSeconds } from "./optimizationOverviewHelpers";

type MetricValue = number | undefined;

const CANDIDATE_KEY_MAP: Record<string, keyof AggregatedCandidate> = {
  score: "score",
  latency: "latencyP50",
  cost: "runtimeCost",
};

type ElapsedDurationProps = {
  startTime: number;
};

const ElapsedDuration: React.FunctionComponent<ElapsedDurationProps> = ({
  startTime,
}) => {
  const [now, setNow] = useState(Date.now());

  useEffect(() => {
    const id = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(id);
  }, []);

  const elapsed = (now - startTime) / 1000;

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
}) => {
  const kpiData = useMemo(() => {
    const totalOptCost = experiments.reduce(
      (sum, e) => sum + (e.total_estimated_cost ?? 0),
      0,
    );

    let totalDuration: number | undefined;
    if (!isInProgress && experiments.length > 0) {
      // The run's completion time is the correct end; fall back to the latest
      // trial's created_at only when last_updated_at is unavailable.
      const latestCreatedAt = experiments.reduce(
        (latest, e) => (e.created_at > latest ? e.created_at : latest),
        experiments[0].created_at,
      );
      totalDuration = getOptimizationDurationSeconds(
        optimizationCreatedAt,
        optimizationLastUpdatedAt ?? latestCreatedAt,
      );
    }

    return { totalOptCost, totalDuration };
  }, [
    experiments,
    optimizationCreatedAt,
    optimizationLastUpdatedAt,
    isInProgress,
  ]);

  const startTime = useMemo(() => {
    if (!optimizationCreatedAt) return undefined;
    return new Date(optimizationCreatedAt).getTime();
  }, [optimizationCreatedAt]);

  const configs = getMetricKPICardConfigs({ isTestSuite, objectiveName });

  return (
    <div className="grid grid-cols-4 gap-4">
      {configs.map((config) => {
        const field = CANDIDATE_KEY_MAP[config.key];
        return (
          <MetricKPICard
            key={config.key}
            icon={config.icon}
            label={config.label}
            baseline={baselineCandidate?.[field] as MetricValue}
            current={bestCandidate?.[field] as MetricValue}
            formatter={config.formatter}
            trend={config.trend}
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
        {isInProgress && startTime != null ? (
          <ElapsedDuration startTime={startTime} />
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
