import React from "react";
import useAiSpendLaneBreakdown from "@/api/ai-spend/useAiSpendLaneBreakdown";
import { Sheet, SheetContent, SheetTopBar } from "@/ui/sheet";
import { Skeleton } from "@/ui/skeleton";
import { formatCost } from "@/lib/money";
import { cn } from "@/lib/utils";
import { laneWeight } from "./utils";

interface LaneBreakdownPanelProps {
  laneKey: string | null;
  projectName: string;
  intervalStart: string;
  intervalEnd: string;
  userUuid?: string;
  onClose: () => void;
}

const LaneBreakdownPanel: React.FC<LaneBreakdownPanelProps> = ({
  laneKey,
  projectName,
  intervalStart,
  intervalEnd,
  userUuid,
  onClose,
}) => {
  const { data, isPending, isError } = useAiSpendLaneBreakdown(
    {
      laneKey: laneKey ?? "",
      projectName,
      intervalStart,
      intervalEnd,
      userUuid,
    },
    { enabled: Boolean(laneKey) },
  );

  const maxWeight = Math.max(
    ...(data?.items ?? []).map((item) => laneWeight(item)),
    1,
  );

  const renderBody = () => {
    if (isPending) {
      return (
        <div className="flex flex-col gap-3">
          {[0, 1, 2, 3].map((i) => (
            <Skeleton key={i} className="h-8 w-full" />
          ))}
        </div>
      );
    }

    if (isError || !data) {
      return (
        <div className="comet-body-s text-muted-slate">
          Detailed breakdown isn&apos;t available yet.
        </div>
      );
    }

    if (data.items.length === 0) {
      return (
        <div className="comet-body-s text-muted-slate">
          No items in the selected period.
        </div>
      );
    }

    return (
      <div className="flex flex-col gap-3">
        {data.subtitle && (
          <p className="comet-body-s text-muted-slate">{data.subtitle}</p>
        )}
        <div className="comet-body-xs text-muted-slate">
          {data.item_count} items · {formatCost(data.total_estimated_cost)}
        </div>
        <div className="flex flex-col gap-2">
          {data.items.map((item) => (
            <div key={item.label} className="flex flex-col gap-1">
              <div className="flex items-center justify-between gap-2">
                <span className="comet-body-s truncate text-foreground">
                  {item.label}
                </span>
                <span className="comet-body-s shrink-0 text-muted-slate">
                  {formatCost(item.total_estimated_cost)}
                </span>
              </div>
              <div className="h-1.5 w-full overflow-hidden rounded-full bg-muted">
                <div
                  className={cn("h-full rounded-full bg-primary")}
                  style={{
                    width: `${(laneWeight(item) / maxWeight) * 100}%`,
                  }}
                />
              </div>
            </div>
          ))}
        </div>
      </div>
    );
  };

  return (
    <Sheet
      open={Boolean(laneKey)}
      onOpenChange={(open) => {
        if (!open) onClose();
      }}
    >
      <SheetContent
        header={
          <SheetTopBar title={data?.title ?? "Breakdown"} variant="info" />
        }
      >
        <div className="max-h-full overflow-y-auto p-6">{renderBody()}</div>
      </SheetContent>
    </Sheet>
  );
};

export default LaneBreakdownPanel;
