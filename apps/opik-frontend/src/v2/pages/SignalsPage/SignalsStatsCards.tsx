import React from "react";
import {
  BugPlay,
  Hammer,
  Hash,
  MoveRight,
  TrendingDown,
  TrendingUp,
} from "lucide-react";
import { Card } from "@/ui/card";
import { Skeleton } from "@/ui/skeleton";
import { cn } from "@/lib/utils";
import { SignalsStats, SignalsStatValue } from "@/types/signals";
import { getStatTrend } from "@/v2/pages/SignalsPage/helpers";

const TREND_MOOD_CLASSES: Record<string, string> = {
  positive: "bg-[var(--tag-green-bg)] text-[var(--tag-green-text)]",
  negative: "bg-[var(--tag-red-bg)] text-[var(--tag-red-text)]",
  neutral: "bg-[var(--tag-gray-bg)] text-muted-slate",
};

const TrendBadge: React.FC<{ stat: SignalsStatValue }> = ({ stat }) => {
  const trend = getStatTrend(stat);
  if (!trend) return null;

  const Icon =
    trend.mood === "positive"
      ? TrendingDown
      : trend.mood === "negative"
        ? TrendingUp
        : MoveRight;

  return (
    <div
      className={cn(
        "flex items-center gap-1 rounded-md px-1.5 py-0.5 comet-body-xs",
        TREND_MOOD_CLASSES[trend.mood],
      )}
    >
      <Icon className="size-3" />
      {trend.label}
    </div>
  );
};

type StatCardProps = {
  icon: React.ElementType;
  label: string;
  stat: SignalsStatValue;
  formatValue?: (value: number) => string;
};

const StatCard: React.FC<StatCardProps> = ({
  icon: Icon,
  label,
  stat,
  formatValue = (v) => v.toLocaleString(),
}) => {
  return (
    <Card className="flex flex-col gap-2 px-4 py-3">
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-1.5 text-muted-slate">
          <Icon className="size-3.5" />
          <span className="comet-body-xs">{label}</span>
        </div>
        <TrendBadge stat={stat} />
      </div>
      <div className="comet-body-accented">{formatValue(stat.value)}</div>
    </Card>
  );
};

type SignalsStatsCardsProps = {
  data?: SignalsStats;
  isPending: boolean;
};

const SignalsStatsCards: React.FC<SignalsStatsCardsProps> = ({
  data,
  isPending,
}) => {
  if (isPending || !data) {
    return (
      <div className="grid grid-cols-1 gap-4 md:grid-cols-3">
        {[0, 1, 2].map((i) => (
          <Skeleton key={i} className="h-[78px] w-full rounded-md" />
        ))}
      </div>
    );
  }

  return (
    <div className="grid grid-cols-1 gap-4 md:grid-cols-3">
      <StatCard
        icon={Hash}
        label="Traces affected"
        stat={data.traces_affected}
      />
      <StatCard icon={BugPlay} label="Open issues" stat={data.open_issues} />
      <StatCard
        icon={Hammer}
        label="Resolved this week"
        stat={data.resolved_this_week}
      />
    </div>
  );
};

export default SignalsStatsCards;
