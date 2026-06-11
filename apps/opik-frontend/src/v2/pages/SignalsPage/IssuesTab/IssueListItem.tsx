import React from "react";
import { Hash, ScanEye } from "lucide-react";
import { Issue } from "@/types/signals";
import { cn } from "@/lib/utils";
import { formatDate } from "@/lib/date";
import {
  CATEGORY_LABEL_MAP,
  SEVERITY_DOT_COLOR_MAP,
  SEVERITY_LABEL_MAP,
} from "@/v2/pages/SignalsPage/helpers";

type IssueListItemProps = {
  issue: Issue;
  isActive: boolean;
  onClick: (issue: Issue) => void;
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
        <span
          className={cn(
            "comet-body-xs flex h-5 shrink-0 items-center gap-1 whitespace-nowrap rounded-md border border-border bg-[#F8FAFC] px-1.5 leading-none text-foreground",
            isActive && "bg-background",
          )}
        >
          <span
            className="size-1.5 rounded-full"
            style={{ backgroundColor: SEVERITY_DOT_COLOR_MAP[issue.severity] }}
          />
          {SEVERITY_LABEL_MAP[issue.severity]}
        </span>
      </div>
      <div className="comet-body-xs text-muted-slate">
        {issue.short_description} · {CATEGORY_LABEL_MAP[issue.category]}
      </div>
      <div className="comet-body-xs flex items-center gap-4 text-muted-slate">
        <span className="flex items-center gap-1">
          <Hash className="size-3" />
          Occurrences: {issue.occurrences.toLocaleString()}
        </span>
        <span className="flex items-center gap-1">
          <ScanEye className="size-3" />
          First seen: {formatDate(issue.first_seen_at, { format: "D MMM" })}
        </span>
      </div>
    </button>
  );
};

export default IssueListItem;
