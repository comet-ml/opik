import React from "react";
import { Card } from "@/ui/card";
import { Skeleton } from "@/ui/skeleton";

export interface KpiCardProps {
  icon: React.ComponentType<{ className?: string }>;
  label: string;
  value: string;
  trend?: React.ReactNode;
  loading?: boolean;
}

const KpiCard: React.FC<KpiCardProps> = ({
  icon: Icon,
  label,
  value,
  trend,
  loading,
}) => (
  <Card className="flex min-h-[74px] min-w-0 flex-1 flex-col px-3 py-2">
    <div className="flex w-full items-center gap-1 pb-2">
      <div className="flex min-w-0 flex-1 items-center gap-1">
        <Icon className="size-3 shrink-0 text-light-slate" />
        <span className="comet-body-xs truncate text-muted-slate">{label}</span>
      </div>
      {!loading && trend}
    </div>
    {loading ? (
      <Skeleton className="h-6 w-24" />
    ) : (
      <div className="comet-body-accented truncate text-foreground">
        {value}
      </div>
    )}
  </Card>
);

export default KpiCard;
