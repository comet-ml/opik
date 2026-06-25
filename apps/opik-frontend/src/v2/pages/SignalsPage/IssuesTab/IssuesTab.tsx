import React, { useEffect, useMemo, useState } from "react";
import { keepPreviousData } from "@tanstack/react-query";
import { StringParam, useQueryParam } from "use-query-params";
import {
  AGENT_INSIGHTS_ISSUE_STATUS,
  AgentInsightsIssue,
} from "@/types/signals";
import { ArrowLeft, Inbox, PartyPopper, Radar, Undo2 } from "lucide-react";
import EmptyIssueDetailsIcon from "@/icons/empty-issue-details.svg?react";
import EmptyIssueDetailsDarkIcon from "@/icons/empty-issue-details-dark.svg?react";
import { useTheme } from "@/contexts/theme-provider";
import { THEME_MODE } from "@/constants/theme";
import { Sorting } from "@/types/sorting";
import useAgentInsightsIssuesList from "@/api/signals/useAgentInsightsIssuesList";
import Loader from "@/shared/Loader/Loader";
import { Button } from "@/ui/button";
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
  {
    // Severity is an enum ordered critical→low, so ascending surfaces the most
    // severe issues first.
    value: "severity",
    label: "By severity",
    sorting: [{ id: "severity", desc: false }],
  },
];

const PAGE_SIZE = 100;

const RUNNING_DESC =
  "We're analyzing your traces to detect issues. Results will update automatically — you can leave this page.";

// 28px (h-7) loader-purple tile; icon is black on light / white on dark.
const RunningIcon: React.FC = () => (
  <div className="flex size-7 shrink-0 items-center justify-center rounded-lg bg-[#A78BFA]">
    <Radar className="size-4 text-black dark:text-white" />
  </div>
);

// Indeterminate "running" bar — a fixed-width fill sliding across the track.
const RunningBar: React.FC = () => (
  <div className="relative h-1.5 w-full overflow-hidden rounded-full bg-muted">
    <div className="absolute inset-y-0 left-0 w-2/5 animate-progress-indeterminate rounded-full bg-[#A78BFA]" />
  </div>
);

const DETAIL_SUBTITLE =
  "You'll see a summary, affected traces, and Ollie's suggested fix.";

// Empty detail pane shown alongside the running / all-clear list states.
const DetailPlaceholder: React.FC = () => {
  const { themeMode } = useTheme();
  const Icon =
    themeMode === THEME_MODE.DARK
      ? EmptyIssueDetailsDarkIcon
      : EmptyIssueDetailsIcon;
  return (
    <div className="flex min-w-0 flex-1 flex-col items-center justify-center gap-3 rounded-md border bg-background p-6 text-center">
      {/* Light icon's pedestal is currentColor; the dark variant is self-colored. */}
      <Icon className="h-[68px] w-[63px] text-[#F3F4FE]" />
      <div className="flex flex-col gap-1">
        <span className="comet-body-s-accented text-foreground">
          Your issue details will appear here
        </span>
        <span className="comet-body-xs text-muted-slate">{DETAIL_SUBTITLE}</span>
      </div>
    </div>
  );
};

type IssuesTabProps = {
  projectId: string;
  showResolved?: boolean;
  // A diagnostic is in flight — shown as the first-run progress state when no
  // issues exist yet.
  isRunning?: boolean;
  // Trigger a new run (used by the "All clear" state's link).
  onRunDiagnostic?: () => void;
  // Switch back to the open-issues view (the resolved-view back arrow / link).
  onShowOpenIssues?: () => void;
};

const IssuesTab: React.FC<IssuesTabProps> = ({
  projectId,
  showResolved = false,
  isRunning = false,
  onRunDiagnostic,
  onShowOpenIssues,
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

  const { data, isPending } = useAgentInsightsIssuesList(
    {
      projectId,
      status,
      sorting,
      page: 1,
      size: PAGE_SIZE,
    },
    // Keep the current list visible while a re-sort/refetch is in flight so the
    // layout doesn't collapse to the loader and jump.
    { placeholderData: keepPreviousData },
  );

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

  // Column header title: a back arrow ("← Resolved issues") in the resolved
  // view, plain "Issues" otherwise.
  const titleNode = showResolved ? (
    <button
      type="button"
      onClick={onShowOpenIssues}
      className="comet-body-xs-accented flex items-center gap-1.5 hover:text-primary"
    >
      <ArrowLeft className="size-3.5" />
      Resolved issues
    </button>
  ) : (
    <span className="comet-body-xs-accented">Issues</span>
  );

  const sortSelect = (
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
  );

  if (isPending) {
    return <Loader />;
  }

  if (!issues.length) {
    // A diagnostic is in flight and nothing is reported yet → first-run progress.
    if (isRunning) {
      return (
        <div className="flex min-h-0 flex-1 gap-2">
          <div className="flex w-[360px] shrink-0 flex-col overflow-hidden rounded-md border bg-background">
            <div className="flex h-10 shrink-0 items-center border-b border-border bg-muted px-4">
              <span className="comet-body-xs-accented">Issues</span>
            </div>
            <div className="flex flex-1 flex-col justify-center gap-3 bg-[#C4B5FD1A] p-5">
              <RunningIcon />
              <div className="flex flex-col gap-1">
                <span className="comet-body-s-accented text-foreground">
                  Running your first diagnostic
                </span>
                <span className="comet-body-xs text-muted-slate">
                  {RUNNING_DESC}
                </span>
              </div>
              <RunningBar />
            </div>
          </div>
          <DetailPlaceholder />
        </div>
      );
    }

    // Resolved tab with nothing resolved yet.
    if (showResolved) {
      return (
        <div className="flex min-h-0 flex-1 gap-2">
          <div className="flex w-[360px] shrink-0 flex-col overflow-hidden rounded-md border bg-background">
            <div className="flex h-10 shrink-0 items-center justify-between border-b border-border bg-muted pl-4 pr-2">
              {titleNode}
              {sortSelect}
            </div>
            <div className="flex flex-1 flex-col justify-center gap-3 p-5">
              <div className="flex size-7 items-center justify-center rounded-lg bg-[#89DEFF] dark:bg-[#1E3A47]">
                <Inbox className="size-3.5 text-black dark:text-white" />
              </div>
              <div className="flex flex-col gap-1">
                <span className="comet-body-s-accented text-foreground">
                  Nothing resolved yet
                </span>
                <span className="comet-body-xs text-muted-slate">
                  Issues you resolve will show up here. You can reopen them
                  anytime.
                </span>
              </div>
              {onShowOpenIssues && (
                <button
                  type="button"
                  onClick={onShowOpenIssues}
                  className="comet-body-s-accented flex items-center gap-1.5 text-[var(--color-primary)] hover:underline"
                >
                  <Undo2 className="size-3.5" />
                  Back to open issues
                </button>
              )}
            </div>
          </div>
          <DetailPlaceholder />
        </div>
      );
    }

    // Diagnostics ran but found no open issues → all clear.
    return (
      <div className="flex min-h-0 flex-1 gap-2">
        <div className="flex w-[360px] shrink-0 flex-col overflow-hidden rounded-md border bg-background">
          <div className="flex h-10 shrink-0 items-center border-b border-border bg-muted px-4">
            <span className="comet-body-xs-accented">Issues</span>
          </div>
          <div className="flex flex-1 flex-col justify-center gap-3 p-5">
            <div className="flex size-7 items-center justify-center rounded-lg bg-[#89DEFF] dark:bg-[#1E3A47]">
              <PartyPopper className="size-3.5 text-black dark:text-white" />
            </div>
            <div className="-mt-1.5 flex flex-col gap-1">
              <span className="comet-body-s-accented text-foreground">
                All clear
              </span>
              <span className="comet-body-xs text-muted-slate">
                Run a new diagnostic to check for anything new.
              </span>
            </div>
            {onRunDiagnostic && (
              <Button
                variant="link"
                size="xs"
                onClick={onRunDiagnostic}
                className="h-auto select-none gap-1.5 self-start px-0"
              >
                <Radar className="size-3.5" />
                Run diagnostic
              </Button>
            )}
          </div>
        </div>
        <DetailPlaceholder />
      </div>
    );
  }

  return (
    <div className="flex min-h-0 flex-1 gap-2">
      {/* Issue list */}
      <div className="flex h-full w-[360px] shrink-0 flex-col overflow-hidden rounded-md border bg-background">
        <div className="flex h-10 shrink-0 items-center justify-between border-b border-border bg-muted pl-4 pr-2">
          {titleNode}
          {sortSelect}
        </div>
        <div className="flex min-h-0 flex-1 flex-col overflow-y-auto">
          {isRunning && (
            <div className="flex items-start gap-3 border-b border-border bg-primary-50 px-4 py-3">
              <RunningIcon />
              <div className="flex min-w-0 flex-1 flex-col gap-2.5">
                <div className="flex flex-col gap-0.5">
                  <span className="comet-body-s-accented text-foreground">
                    Running diagnostic
                  </span>
                  <span className="comet-body-xs text-muted-slate">
                    {RUNNING_DESC}
                  </span>
                </div>
                <RunningBar />
              </div>
            </div>
          )}
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
      <div className="h-full min-w-0 flex-1 overflow-hidden rounded-md border bg-background">
        {activeIssue && (
          <IssueDetail issue={activeIssue} projectId={projectId} />
        )}
      </div>
    </div>
  );
};

export default IssuesTab;
