import React from "react";
import { Coins, LucideIcon, Scale, Timer } from "lucide-react";
import isUndefined from "lodash/isUndefined";

import { calcFormatterAwarePercentage } from "@/lib/percentage";
import PercentageTrend, {
  PercentageTrendType,
} from "@/shared/PercentageTrend/PercentageTrend";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import { StatCard } from "@/ui/stat-card";
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
  icon,
  label,
  headerRight,
  children,
}) => (
  <StatCard>
    <StatCard.Header>
      <StatCard.Title icon={icon}>{label}</StatCard.Title>
      {headerRight}
    </StatCard.Header>
    {children}
  </StatCard>
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
          <PercentageTrend percentage={percentage} trend={trend} size="sm" />
        ) : undefined
      }
    >
      {isUndefined(current) ? (
        <StatCard.Value className="text-muted-slate">-</StatCard.Value>
      ) : (
        <TooltipWrapper content={String(current)}>
          <StatCard.Value>{formatter(current)}</StatCard.Value>
        </TooltipWrapper>
      )}
      {hasComparison && (
        <StatCard.Delta
          from={formatter(baseline as number)}
          to={formatter(current as number)}
        />
      )}
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
    icon: Scale,
    label: getObjectiveLabel(options?.isTestSuite, options?.objectiveName),
    formatter: formatAsPercentage,
  },
  {
    key: "latency",
    icon: Timer,
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
