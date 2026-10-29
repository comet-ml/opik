import React from "react";
import { Hash, ScanEye } from "lucide-react";
import {
  AGENT_INSIGHTS_ISSUE_STATUS,
  AgentInsightsIssue,
} from "@/types/signals";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import { cn } from "@/lib/utils";
import { formatDate } from "@/lib/date";
import SeverityTag from "@/v2/pages/SignalsPage/IssuesTab/SeverityTag";
import {
  CLOSED_STATUS_META,
  formatOccurrences,
} from "@/v2/pages/SignalsPage/helpers";

type IssueListItemProps = {
  issue: AgentInsightsIssue;
  isActive: boolean;
  onClick: (issue: AgentInsightsIssue) => void;
};

const ClosedMeta: React.FC<{
  status:
    | AGENT_INSIGHTS_ISSUE_STATUS.resolved
    | AGENT_INSIGHTS_ISSUE_STATUS.closed;
  changedAt: string;
  changedBy?: string;
}> = ({ status, changedAt, changedBy }) => {
  const Icon = CLOSED_STATUS_META[status].icon;
  // One string in one element: browser translation re-parents bare text nodes.
  const text = [
    status === AGENT_INSIGHTS_ISSUE_STATUS.closed ? "Closed" : "Resolved",
    formatDate(changedAt, { format: "MMM D" }),
    changedBy && `by ${changedBy}`,
  ]
    .filter(Boolean)
    .join(" ");
  return (
    <span className="flex min-w-0 items-center gap-1">
      <Icon className="size-3 shrink-0" />
      <span className="truncate">{text}</span>
    </span>
  );
};

const IssueListItem: React.FC<IssueListItemProps> = ({
  issue,
  isActive,
  onClick,
}) => {
  // Closed issues show who closed them and when; older ones without that keep "Last seen".
  const closedAt =
    issue.status !== AGENT_INSIGHTS_ISSUE_STATUS.open
      ? issue.status_changed_at
      : undefined;
  const showClosedMeta = Boolean(closedAt);
  return (
    <button
      type="button"
      onClick={() => onClick(issue)}
      className={cn(
        "flex w-full flex-col gap-1.5 border-b border-border p-3 text-left transition-colors hover:bg-muted/50",
        isActive && "bg-primary-100 hover:bg-primary-100",
      )}
    >
      <div className="flex items-center gap-2">
        <TooltipWrapper content={issue.name}>
          <span className="comet-body-xs-accented min-w-0 truncate">
            {issue.name}
          </span>
        </TooltipWrapper>
        {issue.severity && (
          <SeverityTag severity={issue.severity} className="ml-auto" />
        )}
      </div>
      {issue.description && (
        <div
          className={cn(
            "comet-body-xs line-clamp-2",
            isActive ? "text-foreground" : "text-muted-slate",
          )}
        >
          {issue.description}
        </div>
      )}
      <div className="comet-body-xs flex items-center gap-4 text-muted-slate">
        <span className="flex items-center gap-1">
          <Hash className="size-3" />
          Occurrences:{" "}
          {formatOccurrences(
            issue.total_occurrences,
            issue.latest_count,
            issue.days_reported,
          )}
        </span>
        {closedAt && issue.status !== AGENT_INSIGHTS_ISSUE_STATUS.open && (
          <ClosedMeta
            status={issue.status}
            changedAt={closedAt}
            changedBy={issue.status_changed_by}
          />
        )}
        {!showClosedMeta && issue.last_seen && (
          <span className="flex items-center gap-1">
            <ScanEye className="size-3" />
            Last seen: {formatDate(issue.last_seen, { format: "D MMM" })}
          </span>
        )}
      </div>
    </button>
  );
};

export default IssueListItem;
