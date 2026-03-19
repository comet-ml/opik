import React, { useMemo, useState, useEffect } from "react";
import { Coins } from "lucide-react";

import {
  KPICard,
  MetricKPICard,
  getMetricKPICardConfigs,
} from "@/components/pages-shared/experiments/KPICard/KPICard";
import {
  formatAsDuration,
  formatAsCurrency,
} from "@/lib/optimization-formatters";
import { Experiment } from "@/types/datasets";
import { AggregatedCandidate } from "@/types/optimizations";

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

  return (
    <span className="comet-body-xs text-muted-slate">
      {formatAsDuration(elapsed)} total
    </span>
  );
};

type OptimizationKPICardsProps = {
  experiments: Experiment[];
  baselineCandidate?: AggregatedCandidate;
  bestCandidate?: AggregatedCandidate;
  isEvaluationSuite?: boolean;
  objectiveName?: string;
  optimizationCreatedAt?: string;
  isInProgress?: boolean;
};

const OptimizationKPICards: React.FunctionComponent<
  OptimizationKPICardsProps
> = ({
  experiments,
  baselineCandidate,
  bestCandidate,
  isEvaluationSuite,
  objectiveName,
  optimizationCreatedAt,
  isInProgress,
}) => {
  const kpiData = useMemo(() => {
    const totalOptCost = experiments.reduce(
      (sum, e) => sum + (e.total_estimated_cost ?? 0),
      0,
    );

    let totalDuration: number | undefined;
    if (optimizationCreatedAt && experiments.length > 0 && !isInProgress) {
      const start = new Date(optimizationCreatedAt).getTime();
      const end = new Date(
        experiments.reduce(
          (latest, e) => (e.created_at > latest ? e.created_at : latest),
          experiments[0].created_at,
        ),
      ).getTime();
      totalDuration = (end - start) / 1000;
    }

    return { totalOptCost, totalDuration };
  }, [experiments, optimizationCreatedAt, isInProgress]);

  const startTime = useMemo(() => {
    if (!optimizationCreatedAt) return undefined;
    return new Date(optimizationCreatedAt).getTime();
  }, [optimizationCreatedAt]);

  const configs = getMetricKPICardConfigs({ isEvaluationSuite, objectiveName });

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
        <div className="flex items-baseline gap-1.5">
          <span className="comet-body-s-accented">
            {kpiData.totalOptCost > 0
              ? formatAsCurrency(kpiData.totalOptCost)
              : "-"}
          </span>
          {isInProgress && startTime != null ? (
            <ElapsedDuration startTime={startTime} />
          ) : (
            kpiData.totalDuration != null &&
            kpiData.totalDuration > 0 && (
              <span className="comet-body-xs text-muted-slate">
                {formatAsDuration(kpiData.totalDuration)} total
              </span>
            )
          )}
        </div>
      </KPICard>
    </div>
  );
};

export default OptimizationKPICards;
