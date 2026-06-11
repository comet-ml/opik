import React, { useMemo } from "react";
import { keepPreviousData } from "@tanstack/react-query";
import {
  ArrowRightToLine,
  CircleDollarSign,
  MessagesSquare,
  PiggyBank,
  User,
} from "lucide-react";
import { cn } from "@/lib/utils";
import PercentageTrend, {
  PercentageTrendType,
} from "@/shared/PercentageTrend/PercentageTrend";
import useAiSpendSummary from "@/api/ai-spend/useAiSpendSummary";
import { useAiSpend } from "@/contexts/AiSpendContext";
import KpiCard from "./KpiCard";
import {
  NO_DATA,
  SpendMetric,
  formatSpendCount,
  formatSpendUsd,
  getSpendTrendPercentage,
} from "@/lib/aiSpend";

const EMPTY_METRIC: SpendMetric = { current: null, previous: null };

interface HomeSummaryCardsProps {
  intervalStart: string;
  intervalEnd: string;
}

const HomeSummaryCards: React.FC<HomeSummaryCardsProps> = ({
  intervalStart,
  intervalEnd,
}) => {
  const { projectName } = useAiSpend();

  const { data, isPending, isPlaceholderData } = useAiSpendSummary(
    {
      projectName,
      intervalStart,
      intervalEnd,
    },
    { placeholderData: keepPreviousData },
  );

  const metrics = useMemo(() => {
    const map: Record<string, SpendMetric> = {};
    (data?.results ?? []).forEach((result) => {
      map[result.name] = {
        current: result.current,
        previous: result.previous,
      };
    });
    return map;
  }, [data]);

  const hasData = useMemo(
    () =>
      (data?.results ?? []).some(
        (r) => (r.current ?? 0) !== 0 || (r.previous ?? 0) !== 0,
      ),
    [data],
  );
  const showData = !isPending && hasData;

  const get = (name: string): SpendMetric => metrics[name] ?? EMPTY_METRIC;
  const totalSpend = get("total_spend");
  const avgCost = get("avg_cost_per_user");
  const totalMessages = get("total_messages");
  const activeUsers = get("active_users");
  const totalUsers = get("total_users");

  const renderTrend = (
    metric: SpendMetric,
    trend: PercentageTrendType = "inverted",
  ) => (
    <PercentageTrend
      percentage={showData ? getSpendTrendPercentage(metric) : undefined}
      trend={trend}
    />
  );

  const usd = (metric: SpendMetric) =>
    showData ? formatSpendUsd(metric.current) : NO_DATA;
  const activeUsersValue =
    showData && activeUsers.current !== null
      ? `${formatSpendCount(activeUsers.current)}/${formatSpendCount(
          totalUsers.current,
        )}`
      : NO_DATA;

  return (
    <div
      className={cn(
        "flex items-start gap-2 transition-opacity",
        isPlaceholderData && "opacity-60",
      )}
    >
      <KpiCard
        icon={CircleDollarSign}
        label="Total spend"
        value={usd(totalSpend)}
        trend={renderTrend(totalSpend)}
        loading={isPending}
      />
      <KpiCard
        icon={PiggyBank}
        label="Budget remaining"
        value={NO_DATA}
        loading={isPending}
      />
      <KpiCard
        icon={ArrowRightToLine}
        label="Active users"
        value={activeUsersValue}
        loading={isPending}
      />
      <KpiCard
        icon={User}
        label="Avg cost per user"
        value={usd(avgCost)}
        trend={renderTrend(avgCost)}
        loading={isPending}
      />
      <KpiCard
        icon={MessagesSquare}
        label="Total messages"
        value={showData ? formatSpendCount(totalMessages.current) : NO_DATA}
        trend={renderTrend(totalMessages)}
        loading={isPending}
      />
    </div>
  );
};

export default HomeSummaryCards;
