import React, { useEffect, useMemo, useState } from "react";
import { keepPreviousData } from "@tanstack/react-query";
import { StringParam, useQueryParam } from "use-query-params";
import {
  ArrowUpRight,
  ChevronDown,
  Clock,
  Inbox,
  PartyPopper,
  Radar,
  RotateCcw,
  TriangleAlert,
  Undo2,
} from "lucide-react";
import {
  AGENT_INSIGHTS_ISSUE_STATUS,
  AgentInsightsIssue,
} from "@/types/signals";
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
import { getRunFailureCopy } from "@/v2/pages/SignalsPage/runFailureCopy";
import { OpikEvent, trackEvent } from "@/lib/analytics/tracking";

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

const ListColumn: React.FC<{
  title?: React.ReactNode;
  actions?: React.ReactNode;
  children: React.ReactNode;
  className?: string;
}> = ({ title, actions, children, className }) => (
  <div
    className={cn(
      "flex min-h-0 w-full flex-1 flex-col overflow-hidden rounded-md border bg-background",
      className,
    )}
  >
    {title && (
      <div className="flex h-10 shrink-0 items-center justify-between gap-2 border-b border-border bg-soft-background px-3">
        {title}
        {actions}
      </div>
    )}
    {children}
  </div>
);

const ClosedIssuesButton: React.FC<{ count: number; onClick: () => void }> = ({
  count,
  onClick,
}) => (
  <Button
    variant="outline"
    size="2xs"
    className="h-7 w-full shrink-0 justify-between rounded px-2"
    onClick={onClick}
  >
    Closed issues{count > 0 && ` (${count})`}
    <ArrowUpRight className="size-3" />
  </Button>
);

const DetailColumn: React.FC<{
  issue?: AgentInsightsIssue;
  projectId: string;
  canConfigure: boolean;
  canCloseAsNotUseful: boolean;
}> = ({ issue, projectId, canConfigure, canCloseAsNotUseful }) => (
  <div className="flex min-h-0 min-w-0 flex-1 flex-col overflow-hidden rounded-md border bg-background">
    {issue ? (
      <IssueDetail
        issue={issue}
        projectId={projectId}
        canConfigure={canConfigure}
        canCloseAsNotUseful={canCloseAsNotUseful}
      />
    ) : (
      <DetailPlaceholder />
    )}
  </div>
);

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

const RunningBar: React.FC = () => (
  <div className="relative h-1.5 w-full overflow-hidden rounded-full bg-muted">
    <div className="absolute inset-y-0 left-0 w-2/5 animate-progress-indeterminate rounded-full bg-[#A78BFA]" />
  </div>
);

// Raw backend failure detail (job.last_failure_detail), as regular muted text.
const FailureDetailText: React.FC<{ detail?: string; className?: string }> = ({
  detail,
  className,
}) =>
  detail ? (
    <p
      className={cn(
        "comet-body-xs whitespace-pre-wrap break-words text-muted-slate",
        className,
      )}
    >
      {detail}
    </p>
  ) : null;

const TryAgainButton: React.FC<{ onClick?: () => void }> = ({ onClick }) =>
  onClick ? (
    <Button
      variant="link"
      size="2xs"
      onClick={onClick}
      className="h-auto select-none gap-1 self-start px-0"
    >
      <RotateCcw className="size-3" />
      Try again
    </Button>
  ) : null;

// Banner above an existing issue list: inline "Show details" + Try again.
const FailedBanner: React.FC<{
  reason?: string;
  detail?: string;
  onRetry?: () => void;
}> = ({ reason, detail, onRetry }) => {
  const { title, description } = getRunFailureCopy(reason);
  const [open, setOpen] = useState(false);
  return (
    <div className="flex items-start gap-3 border-b border-border bg-[#F146681A] px-4 py-3">
      <IconTile className="bg-[#F14668]">
        <TriangleAlert className="size-4 text-white" />
      </IconTile>
      <div className="flex min-w-0 flex-1 flex-col gap-2">
        <div className="flex flex-col gap-0.5">
          <span className="comet-body-s-accented text-foreground">{title}</span>
          <span className="comet-body-xs text-muted-slate">
            {description}
            {detail && (
              <>
                {" "}
                <button
                  type="button"
                  onClick={() => setOpen((o) => !o)}
                  className="text-muted-slate underline underline-offset-2 hover:opacity-80"
                >
                  {open ? "Hide details" : "Show details"}
                </button>
              </>
            )}
          </span>
          {open && <FailureDetailText detail={detail} className="mt-1" />}
        </div>
        <TryAgainButton onClick={onRetry} />
      </div>
    </div>
  );
};

// Rounds down to a "+" bucket for large counts (e.g. 4123 -> "4,100+").
const formatTraceCount = (n: number): string =>
  n >= 100 ? `${(Math.floor(n / 100) * 100).toLocaleString()}+` : `${n}`;

// Shown when the last scan is old — surfaces how many last-24h traces a re-run
// would analyze.
const StaleResultsBanner: React.FC<{
  days: number;
  traceCount: number;
  onRun?: () => void;
}> = ({ days, traceCount, onRun }) => (
  <div className="flex items-start gap-3 border-b border-border bg-[#FB923C1A] px-4 py-3">
    <IconTile className="bg-[#FB923C]">
      <Clock className="size-4 text-white" />
    </IconTile>
    <div className="flex min-w-0 flex-1 flex-col gap-2">
      <div className="flex flex-col gap-0.5">
        <span className="comet-body-s-accented text-foreground">
          Results might be outdated
        </span>
        <span className="comet-body-xs text-muted-slate">
          Last diagnostic ran over {days} {days === 1 ? "day" : "days"} ago. Run
          a new one to analyze {formatTraceCount(traceCount)}{" "}
          {traceCount === 1 ? "trace" : "traces"} from the last 24 hours.
        </span>
      </div>
      {onRun && (
        <Button
          variant="link"
          size="2xs"
          onClick={onRun}
          className="h-auto select-none gap-1.5 self-start px-0"
        >
          <Radar className="size-3" />
          Run diagnostic
        </Button>
      )}
    </div>
  </div>
);

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

const DetailPlaceholder: React.FC = () => {
  const { themeMode } = useTheme();
  const Icon =
    themeMode === THEME_MODE.DARK
      ? EmptyIssueDetailsDarkIcon
      : EmptyIssueDetailsIcon;
  return (
    <div className="flex min-h-0 flex-1 flex-col items-center justify-center gap-3 p-6 text-center">
      <Icon className="h-[68px] w-[63px] text-toggle-outline-active" />
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
  // Open issues, or one of the Closed issues tabs (resolved / closed as not useful).
  status?: AGENT_INSIGHTS_ISSUE_STATUS;
  // Closed issues tabs, shown above the list.
  tabs?: React.ReactNode;
  isRunning?: boolean;
  failedReason?: string;
  failedDetail?: string;
  isStale?: boolean;
  staleTraceCount?: number;
  staleDays?: number;
  canConfigure?: boolean;
  canCloseAsNotUseful?: boolean;
  closedCount?: number;
  onRunDiagnostic?: () => void;
  onShowOpenIssues?: () => void;
  onShowClosedIssues?: () => void;
};

export const ISSUES_LIST_ATTRIBUTE = "data-issues-list";

const IssuesTab: React.FC<IssuesTabProps> = ({
  projectId,
  status = AGENT_INSIGHTS_ISSUE_STATUS.open,
  tabs,
  isRunning = false,
  failedReason,
  failedDetail,
  isStale = false,
  staleTraceCount = 0,
  staleDays = 0,
  canConfigure = false,
  canCloseAsNotUseful = false,
  closedCount = 0,
  onRunDiagnostic,
  onShowOpenIssues,
  onShowClosedIssues,
}) => {
  const showClosed = status !== AGENT_INSIGHTS_ISSUE_STATUS.open;
  const handleTryAgain = () => {
    trackEvent(OpikEvent.DIAGNOSTICS_TRY_AGAIN_CLICKED, {
      project_id: projectId,
      reason: failedReason,
    });
    onRunDiagnostic?.();
  };
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
    {
      // Keep the list while re-sorting, but not across a status change (tabs).
      placeholderData: (previous, previousQuery) =>
        (previousQuery?.queryKey[1] as { status?: string } | undefined)
          ?.status === status
          ? keepPreviousData(previous)
          : undefined,
    },
  );

  const issues = useMemo(() => data?.content ?? [], [data]);

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

  const isWide = useMediaQuery("(min-width: 1024px)");
  const [listOpen, setListOpen] = useState(false);

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
  // Run status shows only on the open-issues view, never on Resolved.
  // Precedence: running/failed take over; stale is a softer nudge.
  const running = isRunning && !showClosed;
  const isFailed = !showClosed && Boolean(failedReason) && !isRunning;
  const stale = isStale && !showClosed && !running && !isFailed;

  const renderListBody = () => {
    if (hasIssues) {
      return (
        <div
          {...{ [ISSUES_LIST_ATTRIBUTE]: "" }}
          className="flex min-h-0 flex-1 flex-col overflow-y-auto"
        >
          {isFailed && (
            <FailedBanner
              reason={failedReason}
              detail={failedDetail}
              onRetry={handleTryAgain}
            />
          )}
          {stale && (
            <StaleResultsBanner
              days={staleDays}
              traceCount={staleTraceCount}
              onRun={onRunDiagnostic}
            />
          )}
          {running && (
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

    if (running) {
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

    if (isFailed) {
      const { title, description } = getRunFailureCopy(failedReason);
      return (
        <ListEmptyState
          className="bg-[#F146681A]"
          icon={
            <IconTile className="bg-[#F14668]">
              <TriangleAlert className="size-4 text-white" />
            </IconTile>
          }
          title={title}
          description={description}
        >
          <FailureDetailText detail={failedDetail} />
          <TryAgainButton onClick={onRunDiagnostic} />
        </ListEmptyState>
      );
    }

    if (showClosed) {
      const isNotUseful = status === AGENT_INSIGHTS_ISSUE_STATUS.closed;
      return (
        <ListEmptyState
          className="bg-[#89DEFF1A]"
          icon={
            <IconTile className="bg-[var(--upload-chip-icon-bg)]">
              <Inbox className="size-3.5 text-black dark:text-white" />
            </IconTile>
          }
          title={
            isNotUseful
              ? "Nothing closed as Not useful yet"
              : "Nothing resolved yet"
          }
          description={
            isNotUseful
              ? "Issues you close as Not useful will show up here. You can reopen them anytime."
              : "Issues you resolve will show up here. You can reopen them anytime."
          }
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

    return (
      <ListEmptyState
        className="bg-[var(--upload-chip-bg)]"
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

  const detail = (
    <DetailColumn
      issue={activeIssue}
      projectId={projectId}
      canConfigure={canConfigure}
      canCloseAsNotUseful={canCloseAsNotUseful}
    />
  );

  // The Closed issues tabs carry the counts, so the closed list has no header.
  const listTitle = showClosed ? undefined : titleNode;
  const closedIssuesButton =
    !showClosed && onShowClosedIssues ? (
      <ClosedIssuesButton count={closedCount} onClick={onShowClosedIssues} />
    ) : null;

  if (!isWide) {
    if (!hasIssues) {
      return (
        <div className="flex min-h-0 flex-1 flex-col gap-2">
          {tabs}
          <ListColumn title={listTitle}>{renderListBody()}</ListColumn>
          {closedIssuesButton}
        </div>
      );
    }

    return (
      <div className="flex min-h-0 flex-1 flex-col gap-2">
        {tabs}
        {isFailed && (
          <div className="shrink-0 overflow-hidden rounded-md border bg-background">
            <FailedBanner
              reason={failedReason}
              detail={failedDetail}
              onRetry={handleTryAgain}
            />
          </div>
        )}
        {stale && (
          <div className="shrink-0 overflow-hidden rounded-md border bg-background">
            <StaleResultsBanner
              days={staleDays}
              traceCount={staleTraceCount}
              onRun={onRunDiagnostic}
            />
          </div>
        )}
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
              const target = e.target as HTMLElement | null;
              if (target?.closest("[data-radix-popper-content-wrapper]")) {
                e.preventDefault();
              }
            }}
          >
            {!showClosed && (
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
        {closedIssuesButton}
      </div>
    );
  }

  return (
    <div className="flex min-h-0 flex-1 gap-2">
      <div className="flex h-full w-[360px] shrink-0 flex-col gap-2">
        {tabs}
        <ListColumn
          title={listTitle}
          actions={hasIssues && !showClosed ? sortSelect : undefined}
        >
          {renderListBody()}
        </ListColumn>
        {closedIssuesButton}
      </div>
      {detail}
    </div>
  );
};

export default IssuesTab;
