import React from "react";
import { Hash, ScanEye } from "lucide-react";
import { AgentInsightsIssue } from "@/types/signals";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import { cn } from "@/lib/utils";
import { formatDate } from "@/lib/date";
import SeverityTag from "@/v2/pages/SignalsPage/IssuesTab/SeverityTag";

type IssueListItemProps = {
  issue: AgentInsightsIssue;
  isActive: boolean;
  onClick: (issue: AgentInsightsIssue) => void;
};

const IssueListItem: React.FC<IssueListItemProps> = ({
  issue,
  isActive,
  onClick,
}) => {
  return (
    <button
      type="button"
      onClick={() => onClick(issue)}
      className={cn(
        "flex w-full flex-col gap-1.5 border-b border-border px-4 py-3 text-left transition-colors hover:bg-muted/50",
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
        <div className="comet-body-xs line-clamp-2 text-muted-slate">
          {issue.description}
        </div>
      )}
      <div className="comet-body-xs flex items-center gap-4 text-muted-slate">
        <span className="flex items-center gap-1">
          <Hash className="size-3" />
          Occurrences: {issue.total_occurrences.toLocaleString()}
        </span>
        {issue.last_seen && (
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
