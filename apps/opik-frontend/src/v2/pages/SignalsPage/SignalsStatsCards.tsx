import React from "react";
import { BugPlay, Hammer, Hash } from "lucide-react";
import { Card } from "@/ui/card";
import { Skeleton } from "@/ui/skeleton";

type StatCardProps = {
  icon: React.ElementType;
  label: string;
  value: string | number;
};

const StatCard: React.FC<StatCardProps> = ({ icon: Icon, label, value }) => (
  <Card className="flex flex-col gap-2 px-4 py-3">
    <div className="flex items-center gap-1.5 text-muted-slate">
      <Icon className="size-3.5" />
      <span className="comet-body-xs">{label}</span>
    </div>
    <div className="comet-body-accented">
      {typeof value === "number" ? value.toLocaleString() : value}
    </div>
  </Card>
);

type SignalsStatsCardsProps = {
  tracesAffected: number;
  openIssues: number;
  resolved: number;
  isPending: boolean;
  // No completed diagnostic yet → show a placeholder dash instead of zeros.
  hasData: boolean;
};

const SignalsStatsCards: React.FC<SignalsStatsCardsProps> = ({
  tracesAffected,
  openIssues,
  resolved,
  isPending,
  hasData,
}) => {
  if (isPending) {
    return (
      <div className="grid grid-cols-1 gap-4 md:grid-cols-3">
        {[0, 1, 2].map((i) => (
          <Skeleton key={i} className="h-[78px] w-full rounded-md" />
        ))}
      </div>
    );
  }

  const dash = "-";

  return (
    <div className="grid grid-cols-1 gap-4 md:grid-cols-3">
      <StatCard
        icon={Hash}
        label="Affected traces"
        value={hasData ? tracesAffected : dash}
      />
      <StatCard
        icon={BugPlay}
        label="Open issues"
        value={hasData ? openIssues : dash}
      />
      <StatCard
        icon={Hammer}
        label="Resolved this week"
        value={hasData ? resolved : dash}
      />
    </div>
  );
};

export default SignalsStatsCards;
