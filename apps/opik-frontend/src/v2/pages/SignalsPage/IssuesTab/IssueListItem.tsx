import React from "react";
import { Hash, ScanEye } from "lucide-react";
import { AgentInsightsIssue } from "@/types/signals";
import { Tag } from "@/ui/tag";
import { cn } from "@/lib/utils";
import { formatDate } from "@/lib/date";
import {
  STATUS_LABEL_MAP,
  STATUS_TAG_VARIANT_MAP,
} from "@/v2/pages/SignalsPage/helpers";

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
        <span className="comet-body-xs-accented truncate">{issue.name}</span>
        <Tag
          size="sm"
          variant={STATUS_TAG_VARIANT_MAP[issue.status]}
          className="shrink-0"
        >
          {STATUS_LABEL_MAP[issue.status]}
        </Tag>
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
