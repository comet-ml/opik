import React from "react";
import { Clock, Coins, LucideIcon, PenLine } from "lucide-react";
import isUndefined from "lodash/isUndefined";

import { calcFormatterAwarePercentage } from "@/lib/percentage";
import PercentageTrend, {
  PercentageTrendType,
} from "@/shared/PercentageTrend/PercentageTrend";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import {
  formatAsPercentage,
  formatAsDuration,
  formatAsCurrency,
} from "@/lib/optimization-formatters";
import { getObjectiveLabel } from "@/lib/optimizations";

type KPICardProps = {
  icon: LucideIcon;
  label: string;
  headerRight?: React.ReactNode;
  children: React.ReactNode;
};

export const KPICard: React.FunctionComponent<KPICardProps> = ({
  icon: Icon,
  label,
  headerRight,
  children,
}) => (
  <div className="rounded-lg border bg-muted/20 p-4">
    <div className="mb-2 flex items-center justify-between gap-2">
      <div className="flex min-w-0 items-center gap-2">
        <Icon className="size-4 shrink-0 text-muted-slate" />
        <span className="comet-body-s truncate text-muted-slate">{label}</span>
      </div>
      {headerRight}
    </div>
    {children}
  </div>
);

type MetricKPICardProps = {
  icon: LucideIcon;
  label: string;
  baseline?: number;
  current?: number;
  formatter: (value: number) => string;
  trend?: PercentageTrendType;
};

export const MetricKPICard: React.FunctionComponent<MetricKPICardProps> = ({
  icon,
  label,
  baseline,
  current,
  formatter,
  trend = "direct",
}) => {
  const percentage = calcFormatterAwarePercentage(current, baseline, formatter);
  const hasComparison = !isUndefined(baseline) && !isUndefined(current);

  return (
    <KPICard
      icon={icon}
      label={label}
      headerRight={
        !isUndefined(percentage) ? (
          <PercentageTrend percentage={percentage} trend={trend} />
        ) : undefined
      }
    >
      <div className="flex flex-col gap-0.5">
        {isUndefined(current) ? (
          <span className="comet-title-m text-muted-slate">-</span>
        ) : (
          <TooltipWrapper content={String(current)}>
            <span className="comet-title-m">{formatter(current)}</span>
          </TooltipWrapper>
        )}
        {hasComparison && (
          <span className="comet-body-xs text-muted-slate">
            {formatter(baseline as number)} → {formatter(current as number)}
          </span>
        )}
      </div>
    </KPICard>
  );
};

export type MetricKPICardConfig = {
  key: string;
  icon: LucideIcon;
  label: string;
  formatter: (value: number) => string;
  trend?: PercentageTrendType;
};

export const getMetricKPICardConfigs = (options?: {
  isTestSuite?: boolean;
  objectiveName?: string;
}): MetricKPICardConfig[] => [
  {
    key: "score",
    icon: PenLine,
    label: getObjectiveLabel(options?.isTestSuite, options?.objectiveName),
    formatter: formatAsPercentage,
  },
  {
    key: "latency",
    icon: Clock,
    label: "Latency",
    formatter: formatAsDuration,
    trend: "inverted",
  },
  {
    key: "cost",
    icon: Coins,
    label: "Runtime cost",
    formatter: formatAsCurrency,
    trend: "inverted",
  },
];
