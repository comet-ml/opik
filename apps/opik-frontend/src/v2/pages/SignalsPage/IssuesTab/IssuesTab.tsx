import React, { useEffect, useMemo, useState } from "react";
import { StringParam, useQueryParam } from "use-query-params";
import {
  AGENT_INSIGHTS_ISSUE_STATUS,
  AgentInsightsIssue,
} from "@/types/signals";
import { Boxes, Radar } from "lucide-react";
import { Sorting } from "@/types/sorting";
import useAgentInsightsIssuesList from "@/api/signals/useAgentInsightsIssuesList";
import Loader from "@/shared/Loader/Loader";
import NoData from "@/shared/NoData/NoData";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/ui/select";
import IssueListItem from "@/v2/pages/SignalsPage/IssuesTab/IssueListItem";
import IssueDetail from "@/v2/pages/SignalsPage/IssuesTab/IssueDetail";

// Sort values map to the backend's sortable fields (last_seen, total_occurrences).
const SORT_OPTIONS: { value: string; label: string; sorting: Sorting }[] = [
  {
    value: "last_seen",
    label: "By last seen",
    sorting: [{ id: "last_seen", desc: true }],
  },
  {
    value: "total_occurrences",
    label: "By occurrences",
    sorting: [{ id: "total_occurrences", desc: true }],
  },
];

const PAGE_SIZE = 100;

type IssuesTabProps = {
  projectId: string;
  showResolved?: boolean;
  // A diagnostic is in flight — shown as the first-run progress state when no
  // issues exist yet.
  isRunning?: boolean;
};

const IssuesTab: React.FC<IssuesTabProps> = ({
  projectId,
  showResolved = false,
  isRunning = false,
}) => {
  const status = showResolved
    ? AGENT_INSIGHTS_ISSUE_STATUS.resolved
    : AGENT_INSIGHTS_ISSUE_STATUS.open;
  const [sortValue, setSortValue] = useState<string>(SORT_OPTIONS[0].value);
  const [activeIssueId, setActiveIssueId] = useQueryParam(
    "issue",
    StringParam,
    { updateType: "replaceIn" },
  );

  const sorting = useMemo(
    () =>
      SORT_OPTIONS.find((o) => o.value === sortValue)?.sorting ?? [
        { id: "last_seen", desc: true },
      ],
    [sortValue],
  );

  const { data, isPending } = useAgentInsightsIssuesList({
    projectId,
    status,
    sorting,
    page: 1,
    size: PAGE_SIZE,
  });

  const issues = useMemo(() => data?.content ?? [], [data]);

  // Default selection: first issue once data is available.
  useEffect(() => {
    if (
      issues.length > 0 &&
      !issues.some((issue) => issue.id === activeIssueId)
    ) {
      setActiveIssueId(issues[0].id);
    }
  }, [issues, activeIssueId, setActiveIssueId]);

  const activeIssue = useMemo(
    () => issues.find((issue) => issue.id === activeIssueId) ?? issues[0],
    [issues, activeIssueId],
  );

  const handleSelect = (issue: AgentInsightsIssue) =>
    setActiveIssueId(issue.id);

  if (isPending) {
    return <Loader />;
  }

  if (!issues.length) {
    // First run with nothing reported yet: show progress in the list column and
    // the empty detail placeholder, instead of the generic no-data state.
    if (isRunning) {
      return (
        <div className="flex min-h-[500px] gap-2">
          <div className="flex w-[360px] shrink-0 flex-col overflow-hidden rounded-md border bg-background">
            <div className="flex h-10 items-center border-b border-border bg-[#F8FAFC] px-4">
              <span className="comet-body-xs-accented">Issues</span>
            </div>
            <div className="flex flex-1 flex-col justify-center gap-3 p-5">
              <div className="flex size-8 items-center justify-center rounded-lg bg-primary-100">
                <Radar className="size-4 text-[var(--color-primary)]" />
              </div>
              <div className="flex flex-col gap-1">
                <span className="comet-body-s-accented text-foreground">
                  Running your first diagnostic
                </span>
                <span className="comet-body-xs text-muted-slate">
                  We&apos;re analyzing your traces to detect issues. Results
                  will update automatically — you can leave this page.
                </span>
              </div>
              <div className="mt-1 h-1.5 w-full overflow-hidden rounded-full bg-muted">
                <div className="h-full w-2/5 animate-pulse rounded-full bg-[var(--color-primary)]" />
              </div>
            </div>
          </div>

          <div className="flex min-w-0 flex-1 flex-col items-center justify-center gap-3 rounded-md border bg-background p-6 text-center">
            <Boxes className="size-12 text-light-slate" strokeWidth={1.25} />
            <div className="flex flex-col gap-1">
              <span className="comet-body-s-accented text-foreground">
                Select an issue to see details
              </span>
              <span className="comet-body-xs text-muted-slate">
                You&apos;ll see a summary, affected traces, and Ollie&apos;s
                suggested fix.
              </span>
            </div>
          </div>
        </div>
      );
    }

    return (
      <NoData
        title={showResolved ? "No resolved issues" : "No open issues"}
        message={
          showResolved
            ? "Issues you resolve will show up here."
            : "Once we scan your traces, detected issues will show up here."
        }
        className="h-[400px]"
      />
    );
  }

  return (
    <div className="flex min-h-[500px] gap-2">
      {/* Issue list */}
      <div className="flex w-[360px] shrink-0 flex-col overflow-hidden rounded-md border bg-background">
        <div className="flex h-10 items-center justify-between border-b border-border bg-[#F8FAFC] pl-4 pr-2">
          <span className="comet-body-xs-accented">Issues</span>
          <Select value={sortValue} onValueChange={setSortValue}>
            <SelectTrigger className="h-7 w-auto gap-1 border-none bg-transparent px-2 text-xs shadow-none hover:shadow-none [&>svg]:size-3 [&>svg]:opacity-100">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              {SORT_OPTIONS.map((option) => (
                <SelectItem
                  key={option.value}
                  value={option.value}
                  className="text-xs"
                >
                  {option.label}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>
        <div className="flex flex-col overflow-y-auto">
          {issues.map((issue) => (
            <IssueListItem
              key={issue.id}
              issue={issue}
              isActive={issue.id === activeIssue?.id}
              onClick={handleSelect}
            />
          ))}
        </div>
      </div>

      {/* Issue detail */}
      <div className="min-w-0 flex-1 overflow-hidden rounded-md border bg-background">
        {activeIssue && (
          <IssueDetail issue={activeIssue} projectId={projectId} />
        )}
      </div>
    </div>
  );
};

export default IssuesTab;
