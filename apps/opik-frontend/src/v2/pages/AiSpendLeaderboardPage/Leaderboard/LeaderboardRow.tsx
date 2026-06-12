import React from "react";
import { tierCost } from "@/api/ai-spend/claudePricing";
import { ChevronDown } from "lucide-react";
import { cn } from "@/lib/utils";
import { formatSpendCount, formatSpendUsd } from "@/lib/aiSpend";
import { SpendUserRow } from "@/api/ai-spend/useAiSpendUsers";
import UserAvatar from "./UserAvatar";
import UserMetric from "./UserMetric";
import LeaderboardRowDetails from "./LeaderboardRowDetails";

const HIGH_SPEND_FLAG = "high_spend";

interface LeaderboardRowProps {
  row: SpendUserRow;
  rank: number;
  expanded: boolean;
  dimmed?: boolean;
  onToggle: () => void;
  projectName: string;
  intervalStart: string;
  intervalEnd: string;
  detailsLaneKey: string | null;
  onViewLaneDetails: (laneKey: string) => void;
}

const Divider: React.FC = () => (
  <div className="h-14 w-px shrink-0 bg-border" />
);

const LeaderboardRow: React.FC<LeaderboardRowProps> = ({
  row,
  rank,
  expanded,
  dimmed,
  onToggle,
  projectName,
  intervalStart,
  intervalEnd,
  detailsLaneKey,
  onViewLaneDetails,
}) => {
  const hasHighSpend = row.flags?.includes(HIGH_SPEND_FLAG);

  return (
    <div
      className={cn(
        "overflow-hidden rounded-md border bg-background transition-opacity",
        dimmed && "opacity-40",
      )}
    >
      <div
        role="button"
        tabIndex={0}
        aria-expanded={expanded}
        onClick={onToggle}
        onKeyDown={(event) => {
          if (event.key === "Enter" || event.key === " ") {
            event.preventDefault();
            onToggle();
          }
        }}
        className="flex h-14 cursor-pointer items-center gap-3 pl-3 pr-2 transition-colors hover:bg-muted/50"
      >
        <div className="flex size-8 shrink-0 items-center justify-center rounded-md bg-chart-gray-light">
          <span className="comet-body-xs text-muted-slate">{rank}</span>
        </div>

        <Divider />

        <div className="flex w-[183px] shrink-0 items-center gap-2 overflow-hidden">
          <UserAvatar name={row.user_email} />
          <span className="comet-body-xs truncate text-foreground">
            {row.user_email}
          </span>
        </div>

        <Divider />

        <div className="flex h-11 min-w-0 flex-1 items-center justify-between gap-3">
          <div className="flex min-w-[120px] max-w-[180px] shrink-0 items-center justify-center rounded-md border bg-primary-foreground py-1 pl-1 pr-1.5">
            <span className="comet-body-xs min-w-0 truncate text-foreground">
              {row.model}
            </span>
          </div>

          <UserMetric
            className="w-[72px]"
            value={formatSpendUsd(tierCost(row, row.model))}
            label="spend"
          />
          <UserMetric
            className="w-[42px]"
            value={formatSpendCount(row.requests)}
            label="requests"
          />
          <UserMetric
            className="w-[42px]"
            value={formatSpendCount(row.skills)}
            label="skills"
          />
          <UserMetric
            className="w-[42px]"
            value={formatSpendCount(row.mcps)}
            label="MCPs"
          />
          <UserMetric
            className="w-12"
            value={formatSpendCount(row.mcp_calls)}
            label="MCP calls"
          />

          <div className="flex w-[100px] shrink-0 justify-start">
            {hasHighSpend && (
              <div className="flex items-center gap-1 rounded-md border border-muted bg-primary-foreground py-0.5 pl-1 pr-1.5">
                <span className="size-1.5 shrink-0 rounded-full bg-red-500" />
                <span className="comet-body-xs-accented whitespace-nowrap text-foreground">
                  High spend
                </span>
              </div>
            )}
          </div>
        </div>

        <Divider />

        <div className="flex size-6 shrink-0 items-center justify-center text-muted-slate">
          <ChevronDown
            className={cn(
              "size-3 transition-transform",
              expanded && "rotate-180",
            )}
          />
        </div>
      </div>

      {expanded && (
        <LeaderboardRowDetails
          userUuid={row.user_uuid}
          repositories={row.repositories ?? []}
          projectName={projectName}
          intervalStart={intervalStart}
          intervalEnd={intervalEnd}
          activeLaneKey={detailsLaneKey}
          onViewLaneDetails={onViewLaneDetails}
        />
      )}
    </div>
  );
};

export default LeaderboardRow;
