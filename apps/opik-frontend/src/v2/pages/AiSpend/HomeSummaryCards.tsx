import React, { useMemo } from "react";
import {
  CircleDollarSign,
  MessagesSquare,
  PiggyBank,
  User,
  Users,
} from "lucide-react";
import PercentageTrend, {
  PercentageTrendType,
} from "@/shared/PercentageTrend/PercentageTrend";
import useAiSpendSummary from "@/api/ai-spend/useAiSpendSummary";
import { useAiSpend } from "@/contexts/AiSpendContext";
import KpiCard from "./KpiCard";
import {
  NO_DATA,
  SpendMetric,
  SpendWindow,
  formatSpendCount,
  formatSpendUsd,
  getSpendInterval,
  getSpendTrendPercentage,
} from "@/lib/aiSpend";

const EMPTY_METRIC: SpendMetric = { current: null, previous: null };

interface HomeSummaryCardsProps {
  windowDays: SpendWindow;
}

const HomeSummaryCards: React.FC<HomeSummaryCardsProps> = ({ windowDays }) => {
  const { projectName } = useAiSpend();

  const { intervalStart, intervalEnd } = useMemo(
    () => getSpendInterval(windowDays),
    [windowDays],
  );

  const { data, isPending } = useAiSpendSummary({
    projectName,
    intervalStart,
    intervalEnd,
  });

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

  // No data → render the "N/A" empty state instead of zeros/trends.
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
  ) =>
    showData ? (
      <PercentageTrend
        percentage={getSpendTrendPercentage(metric)}
        trend={trend}
      />
    ) : undefined;

  const usd = (metric: SpendMetric) =>
    showData ? formatSpendUsd(metric.current) : NO_DATA;
  const activeUsersValue =
    showData && activeUsers.current !== null
      ? `${formatSpendCount(activeUsers.current)}/${formatSpendCount(
          totalUsers.current,
        )}`
      : NO_DATA;

  return (
    <div className="flex items-start gap-2">
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
        icon={Users}
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
