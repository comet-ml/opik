import React, { useState } from "react";
import { Link } from "@tanstack/react-router";
import { ArrowUpRight, Check } from "lucide-react";
import { Tag } from "@/ui/tag";
import { Separator } from "@/ui/separator";
import { HoverCard, HoverCardContent, HoverCardTrigger } from "@/ui/hover-card";
import { useActiveWorkspaceName } from "@/store/AppStore";
import useAgentInsightsIssuesList from "@/api/signals/useAgentInsightsIssuesList";
import useTracesList from "@/api/traces/useTracesList";
import { AGENT_INSIGHTS_ISSUE_STATUS } from "@/types/signals";
import { COLUMN_TYPE } from "@/types/shared";
import { AUTO_FIRST_RUN_WINDOW_MS } from "@/v2/pages/SignalsPage/helpers";

type DiagnosticsReadyBadgeProps = {
  projectId: string;
  autoRunAt: number;
};

const DiagnosticsReadyBadge: React.FC<DiagnosticsReadyBadgeProps> = ({
  projectId,
  autoRunAt,
}) => {
  const workspaceName = useActiveWorkspaceName();
  // Fetched only once the popover opens, so the badge itself costs no requests. Hovering leaves the report
  // unseen: only visiting the page clears the badge.
  const [open, setOpen] = useState(false);

  const { data: issues } = useAgentInsightsIssuesList(
    { projectId, status: AGENT_INSIGHTS_ISSUE_STATUS.open, page: 1, size: 1 },
    { enabled: open },
  );
  const { data: traces } = useTracesList(
    {
      projectId,
      page: 1,
      size: 1,
      filters: [
        {
          id: "diagnostics-ready-window-start",
          field: "created_at",
          type: COLUMN_TYPE.time,
          operator: ">",
          value: new Date(autoRunAt - AUTO_FIRST_RUN_WINDOW_MS).toISOString(),
        },
        {
          id: "diagnostics-ready-window-end",
          field: "created_at",
          type: COLUMN_TYPE.time,
          operator: "<",
          value: new Date(autoRunAt).toISOString(),
        },
      ],
    },
    { enabled: open },
  );

  const stats = [
    {
      label: "Issues found",
      value: issues?.total,
      color: "bg-chart-violet",
    },
    {
      label: "Traces analyzed",
      value: traces?.total,
      color: "bg-chart-orange",
    },
  ];

  return (
    <HoverCard openDelay={100} open={open} onOpenChange={setOpen}>
      <HoverCardTrigger asChild>
        <Tag size="sm" className="h-5 px-1.5 leading-[18px] text-foreground">
          Ready
        </Tag>
      </HoverCardTrigger>
      <HoverCardContent
        side="right"
        align="start"
        className="w-[227px] p-1"
        onClick={(event) => event.stopPropagation()}
      >
        <div className="flex items-center gap-1 p-1">
          <span className="flex size-4 shrink-0 items-center justify-center rounded-md bg-accent-green">
            <Check className="size-3 text-black" />
          </span>
          <span className="comet-body-xs-accented text-foreground">
            Your first diagnostic is ready
          </span>
        </div>
        <p className="comet-body-xs px-1 pb-1 text-muted-slate">
          Opik automatically analyzed your recent traces to find and rank issues
          by impact.
        </p>
        <Separator className="my-1" />
        <div className="flex flex-col gap-1 p-1">
          {stats.map(({ label, value, color }) => (
            <div key={label} className="flex items-center gap-2">
              <span className={`size-2 shrink-0 rounded-[2px] ${color}`} />
              <span className="comet-body-xs flex-1 text-muted-slate">
                {label}
              </span>
              <span className="comet-body-xs tabular-nums text-foreground">
                {value?.toLocaleString() ?? "–"}
              </span>
            </div>
          ))}
        </div>
        <Separator className="my-1" />
        <Link
          to="/$workspaceName/projects/$projectId/diagnostics"
          params={{ workspaceName, projectId }}
          onClick={() => setOpen(false)}
          className="comet-body-xs flex items-center gap-1 p-1 text-foreground"
        >
          View results
          <ArrowUpRight className="size-3 text-light-slate" />
        </Link>
      </HoverCardContent>
    </HoverCard>
  );
};

export default DiagnosticsReadyBadge;
