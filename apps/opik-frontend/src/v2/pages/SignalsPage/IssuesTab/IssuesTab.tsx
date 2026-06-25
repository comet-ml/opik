import React, { useEffect, useMemo, useState } from "react";
import { keepPreviousData } from "@tanstack/react-query";
import { StringParam, useQueryParam } from "use-query-params";
import {
  AGENT_INSIGHTS_ISSUE_STATUS,
  AgentInsightsIssue,
} from "@/types/signals";
import { ChevronDown, Inbox, PartyPopper, Radar, Undo2 } from "lucide-react";
import EmptyIssueDetailsIcon from "@/icons/empty-issue-details.svg?react";
import EmptyIssueDetailsDarkIcon from "@/icons/empty-issue-details-dark.svg?react";
import { useTheme } from "@/contexts/theme-provider";
import { THEME_MODE } from "@/constants/theme";
import { Sorting } from "@/types/sorting";
import { cn } from "@/lib/utils";
import useAgentInsightsIssuesList from "@/api/signals/useAgentInsightsIssuesList";
import { Button } from "@/ui/button";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/ui/select";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuTrigger,
} from "@/ui/dropdown-menu";
import { useMediaQuery } from "@/hooks/useMediaQuery";
import IssueListItem from "@/v2/pages/SignalsPage/IssuesTab/IssueListItem";
import IssueDetail from "@/v2/pages/SignalsPage/IssuesTab/IssueDetail";
import IssueSeverityBadge from "@/v2/pages/SignalsPage/IssuesTab/IssueSeverityBadge";
import IssuesSkeleton from "@/v2/pages/SignalsPage/IssuesTab/IssuesSkeleton";

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

const DETAIL_SUBTITLE =
  "You'll see a summary, affected traces, and Ollie's suggested fix.";

// ---------------------------------------------------------------------------
// Shared layout pieces — the two columns and small building blocks reused
// across the loading / running / empty / populated states, so the open and
// resolved views render through one flexible code path instead of duplicated
// markup.
// ---------------------------------------------------------------------------

// Left column: bordered card with a header (title + optional actions) and a
// body. The header reuses the page (external container) color from the design
// system so it blends with the surrounding chrome.
const ListColumn: React.FC<{
  title: React.ReactNode;
  actions?: React.ReactNode;
  children: React.ReactNode;
  className?: string;
}> = ({ title, actions, children, className }) => (
  <div
    className={cn(
      "flex h-full w-[360px] shrink-0 flex-col overflow-hidden rounded-md border bg-background",
      className,
    )}
  >
    <div className="flex h-10 shrink-0 items-center justify-between gap-2 border-b border-border bg-soft-background px-3">
      {title}
      {actions}
    </div>
    {children}
  </div>
);

// Right column: bordered card holding the selected issue's detail, or a
// placeholder when nothing is selected (loading / empty list states).
const DetailColumn: React.FC<{
  issue?: AgentInsightsIssue;
  projectId: string;
}> = ({ issue, projectId }) => (
  // No fixed height: flex-1 fills width in the wide row and height in the
  // compact column; min-h-0 lets the inner body scroll in both.
  <div className="flex min-h-0 min-w-0 flex-1 flex-col overflow-hidden rounded-md border bg-background">
    {issue ? (
      <IssueDetail issue={issue} projectId={projectId} />
    ) : (
      <DetailPlaceholder />
    )}
  </div>
);

// 28px colored tile fronting the running banner and empty-state messages.
const IconTile: React.FC<{ className?: string; children: React.ReactNode }> = ({
  className,
  children,
}) => (
  <div
    className={cn(
      "flex size-7 shrink-0 items-center justify-center rounded-lg",
      className,
    )}
  >
    {children}
  </div>
);

// Indeterminate "running" bar — a fixed-width fill sliding across the track.
const RunningBar: React.FC = () => (
  <div className="relative h-1.5 w-full overflow-hidden rounded-full bg-muted">
    <div className="absolute inset-y-0 left-0 w-2/5 animate-progress-indeterminate rounded-full bg-[#A78BFA]" />
  </div>
);

// Centered status message shown in the list column when there are no rows
// (running first diagnostic / nothing resolved / all clear). `className` tints
// the background; `children` holds the trailing action (button / progress bar).
const ListEmptyState: React.FC<{
  icon: React.ReactNode;
  title: string;
  description: string;
  className?: string;
  children?: React.ReactNode;
}> = ({ icon, title, description, className, children }) => (
  <div
    className={cn("flex flex-1 flex-col justify-center gap-3 p-5", className)}
  >
    {icon}
    <div className="flex flex-col gap-1">
      <span className="comet-body-s-accented text-foreground">{title}</span>
      <span className="comet-body-xs text-muted-slate">{description}</span>
    </div>
    {children}
  </div>
);

// Empty detail pane (right column) shown when no issue is selected.
const DetailPlaceholder: React.FC = () => {
  const { themeMode } = useTheme();
  const Icon =
    themeMode === THEME_MODE.DARK
      ? EmptyIssueDetailsDarkIcon
      : EmptyIssueDetailsIcon;
  return (
    <div className="flex min-h-0 flex-1 flex-col items-center justify-center gap-3 p-6 text-center">
      {/* Light icon's pedestal is currentColor; the dark variant is self-colored. */}
      <Icon className="h-[68px] w-[63px] text-[#F3F4FE]" />
      <div className="flex flex-col gap-1">
        <span className="comet-body-s-accented text-foreground">
          Your issue details will appear here
        </span>
        <span className="comet-body-xs text-muted-slate">
          {DETAIL_SUBTITLE}
        </span>
      </div>
    </div>
  );
};

type IssuesTabProps = {
  projectId: string;
  showResolved?: boolean;
  // A diagnostic is in flight — shown as the first-run progress state when no
  // issues exist yet, or as a banner above an existing list.
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

  // Below lg the two-pane collapses: the list becomes a dropdown and the report
  // shows beneath it (mirrors the prompt version-history responsive pattern).
  const isWide = useMediaQuery("(min-width: 1024px)");
  const [listOpen, setListOpen] = useState(false);

  // The list column is always titled "Issues", with a count for the current
  // view (open vs resolved) since the stat cards only summarize open findings.
  // The resolved view is named by the page header (with its own back arrow).
  const issueCount = data?.total ?? issues.length;
  const titleNode = (
    <span className="comet-body-xs-accented">
      Issues{issueCount > 0 && ` (${issueCount})`}
    </span>
  );

  const sortSelect = (
    <Select value={sortValue} onValueChange={setSortValue}>
      <SelectTrigger className="h-7 w-auto gap-1 border-none bg-transparent px-2 text-xs shadow-none hover:bg-muted hover:shadow-none [&>svg]:size-3 [&>svg]:opacity-100">
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
    return <IssuesSkeleton />;
  }

  const hasIssues = issues.length > 0;

  // List body: the scrollable rows (with an optional running banner) when we
  // have issues, otherwise the matching centered status message.
  const renderListBody = () => {
    if (hasIssues) {
      return (
        <div className="flex min-h-0 flex-1 flex-col overflow-y-auto">
          {isRunning && (
            <div className="flex items-start gap-3 border-b border-border bg-primary-50 px-4 py-3">
              <IconTile className="bg-[#A78BFA]">
                <Radar className="size-4 text-black dark:text-white" />
              </IconTile>
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
      );
    }

    // A diagnostic is in flight and nothing is reported yet → first-run progress.
    if (isRunning) {
      return (
        <ListEmptyState
          className="bg-[#C4B5FD1A]"
          icon={
            <IconTile className="bg-[#A78BFA]">
              <Radar className="size-4 text-black dark:text-white" />
            </IconTile>
          }
          title="Running your first diagnostic"
          description={RUNNING_DESC}
        >
          <RunningBar />
        </ListEmptyState>
      );
    }

    // Resolved tab with nothing resolved yet.
    if (showResolved) {
      return (
        <ListEmptyState
          className="bg-[#89DEFF1A]"
          icon={
            <IconTile className="bg-[#89DEFF] dark:bg-[#1E3A47]">
              <Inbox className="size-3.5 text-black dark:text-white" />
            </IconTile>
          }
          title="Nothing resolved yet"
          description="Issues you resolve will show up here. You can reopen them anytime."
        >
          {onShowOpenIssues && (
            <Button
              variant="link"
              size="2xs"
              onClick={onShowOpenIssues}
              className="h-auto select-none gap-1.5 self-start px-0"
            >
              <Undo2 className="size-3" />
              Back to open issues
            </Button>
          )}
        </ListEmptyState>
      );
    }

    // Diagnostics ran but found no open issues → all clear.
    return (
      <ListEmptyState
        className="bg-[#BAE6FD1A]"
        icon={
          <IconTile className="bg-[#89DEFF] dark:bg-[#1E3A47]">
            <PartyPopper className="size-3.5 text-black dark:text-white" />
          </IconTile>
        }
        title="All clear"
        description="Run a new diagnostic to check for anything new."
      >
        {onRunDiagnostic && (
          <Button
            variant="link"
            size="2xs"
            onClick={onRunDiagnostic}
            className="h-auto select-none gap-1.5 self-start px-0"
          >
            <Radar className="size-3" />
            Run diagnostic
          </Button>
        )}
      </ListEmptyState>
    );
  };

  const detail = <DetailColumn issue={activeIssue} projectId={projectId} />;

  // Empty states render as one full-width card at every width: there's no list
  // to collapse and no report to show, so the two-pane / dropdown split would
  // only look unbalanced. Keeps the resolved (and open) empty states responsive
  // and identical across breakpoints.
  if (!hasIssues) {
    return (
      <div className="flex min-h-0 flex-1">
        <ListColumn className="w-full" title={titleNode}>
          {renderListBody()}
        </ListColumn>
      </div>
    );
  }

  // Compact (< lg): collapse the list into a dropdown above the report.
  if (!isWide) {
    return (
      <div className="flex min-h-0 flex-1 flex-col gap-2">
        <DropdownMenu open={listOpen} onOpenChange={setListOpen} modal={false}>
          <DropdownMenuTrigger asChild>
            <Button
              variant="outline"
              className="h-9 w-full shrink-0 justify-between gap-2 px-3 font-normal"
            >
              <span className="flex min-w-0 items-center gap-2">
                <span className="comet-body-xs-accented shrink-0 text-muted-slate">
                  Issues ({issueCount}):
                </span>
                <IssueSeverityBadge severity={activeIssue?.severity} />
                <span className="comet-body-xs-accented truncate text-foreground">
                  {activeIssue?.name}
                </span>
              </span>
              <ChevronDown className="size-4 shrink-0 text-light-slate" />
            </Button>
          </DropdownMenuTrigger>
          <DropdownMenuContent
            align="start"
            className="max-h-[60vh] w-[var(--radix-dropdown-menu-trigger-width)] overflow-y-auto p-0"
            onInteractOutside={(e) => {
              // Keep the panel open while using the nested sort Select — its
              // options render in a separate popper portal counted as "outside".
              const target = e.target as HTMLElement | null;
              if (target?.closest("[data-radix-popper-content-wrapper]")) {
                e.preventDefault();
              }
            }}
          >
            {!showResolved && (
              <div className="sticky top-0 z-10 flex h-10 items-center justify-between gap-2 border-b border-border bg-soft-background pl-3 pr-2">
                {titleNode}
                {sortSelect}
              </div>
            )}
            {issues.map((issue) => (
              <IssueListItem
                key={issue.id}
                issue={issue}
                isActive={issue.id === activeIssue?.id}
                onClick={(i) => {
                  handleSelect(i);
                  setListOpen(false);
                }}
              />
            ))}
          </DropdownMenuContent>
        </DropdownMenu>
        {detail}
      </div>
    );
  }

  // Wide (xl+): persistent two-pane. Sorting shows only in the open view, and
  // only once there are issues to sort.
  return (
    <div className="flex min-h-0 flex-1 gap-2">
      <ListColumn
        title={titleNode}
        actions={hasIssues && !showResolved ? sortSelect : undefined}
      >
        {renderListBody()}
      </ListColumn>
      {detail}
    </div>
  );
};

export default IssuesTab;
