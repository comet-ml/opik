import React, { useEffect, useMemo, useRef, useState } from "react";
import { Navigate, useNavigate, useParams } from "@tanstack/react-router";
import { useQueryClient } from "@tanstack/react-query";
import { CircleCheck, Play, ThumbsDown } from "lucide-react";
import { StringParam, useQueryParam } from "use-query-params";
import { useActiveProjectId } from "@/store/AppStore";
import usePluginsStore from "@/store/PluginsStore";
import { useIsFeatureEnabled } from "@/contexts/feature-toggles-provider";
import { usePermissions } from "@/contexts/PermissionsContext";
import { FeatureToggleKeys } from "@/types/feature-toggles";
import {
  AGENT_INSIGHTS_ISSUES_KEY,
  AGENT_INSIGHTS_JOB_KEY,
  OLLIE_CREDITS_KEY,
  TRACES_KEY,
} from "@/api/api";
import { formatDate } from "@/lib/date";
import PageBodyScrollContainer from "@/v2/layout/PageBodyScrollContainer/PageBodyScrollContainer";
import BackButton from "@/shared/BackButton/BackButton";
import { Button } from "@/ui/button";
import { Tabs, TabsList, TabsTrigger } from "@/ui/tabs";
import {
  AGENT_INSIGHTS_ISSUE_STATUS,
  AGENT_INSIGHTS_JOB_STATUS,
  AgentInsightsIssue,
} from "@/types/signals";
import useAgentInsightsIssuesList from "@/api/signals/useAgentInsightsIssuesList";
import useTracesList from "@/api/traces/useTracesList";
import { COLUMN_TYPE } from "@/types/shared";
import useAgentInsightsJob from "@/api/signals/useAgentInsightsJob";
import useOllieCredits from "@/api/ollie/useOllieCredits";
import { OUT_OF_CREDITS_FAILURE_REASON } from "@/types/ollie-reports";
import useTriggerAgentInsightsJobMutation from "@/api/signals/useTriggerAgentInsightsJobMutation";
import useDiagnosticsRunState from "@/hooks/useDiagnosticsRunState";
import useDiagnosticsSeen from "@/hooks/useDiagnosticsSeen";
import { getRunFailureCopy } from "@/v2/pages/SignalsPage/runFailureCopy";
import { OpikEvent, trackEvent } from "@/lib/analytics/tracking";
import { useToast } from "@/ui/use-toast";
import SignalsStatsCards from "@/v2/pages/SignalsPage/SignalsStatsCards";
import IssuesTab from "@/v2/pages/SignalsPage/IssuesTab/IssuesTab";
import DiagnosticsEmptyState from "@/v2/pages/SignalsPage/DiagnosticsEmptyState";
import AutoRunToggle from "@/v2/pages/SignalsPage/AutoRunToggle";
import OutOfCreditsButton from "@/v2/pages/SignalsPage/OutOfCreditsButton";
import DiagnosticsSettingsMenu from "@/v2/pages/SignalsPage/DiagnosticsSettingsMenu";
import GuidanceSheet from "@/v2/pages/SignalsPage/GuidanceSheet";
import GuidanceChangedCallout from "@/v2/pages/SignalsPage/GuidanceChangedCallout";
import SignalsPageSkeleton from "@/v2/pages/SignalsPage/SignalsPageSkeleton";
import useColumnsOverflow from "@/v2/pages/SignalsPage/useColumnsOverflow";
import {
  countAffectedTraces,
  getHeaderControls,
  isGuidanceOutdated,
} from "@/v2/pages/SignalsPage/helpers";
import {
  AUTO_FIRST_RUN_WINDOW_MS,
  AUTO_RUN_MAX_DURATION_MS,
} from "@/constants/diagnostics";

const RUN_POLL_INTERVAL_MS = 8000;
const ELIGIBILITY_POLL_INTERVAL_MS = 30_000;
const HOUR_MS = 60 * 60 * 1000;
const DAY_MS = 24 * HOUR_MS;
const STALE_AFTER_MS = 3 * DAY_MS;

const LAYOUT = {
  scrolling: {
    body: "flex flex-col gap-4 px-6 pb-2",
    columns:
      "sticky top-2 flex flex-col h-[calc(100vh-var(--header-height)-var(--banner-height)-16px)]",
  },
  fitted: {
    body: "flex min-h-0 flex-1 flex-col gap-4 px-6 pb-2",
    columns: "flex min-h-0 flex-1 flex-col",
  },
};

const maxUpdatedAt = (issues: AgentInsightsIssue[]): number =>
  issues.reduce((max, issue) => {
    const t = issue.last_updated_at ? Date.parse(issue.last_updated_at) : 0;
    return Number.isFinite(t) && t > max ? t : max;
  }, 0);

const CLOSED_TABS = {
  resolved: "resolved",
  notUseful: "not_useful",
} as const;

const SignalsPage: React.FC<{ showClosed?: boolean }> = ({
  showClosed = false,
}) => {
  const projectId = useActiveProjectId()!;
  const navigate = useNavigate();
  const { workspaceName } = useParams({ strict: false }) as {
    workspaceName: string;
  };
  const goToOpenIssues = () =>
    navigate({
      to: "/$workspaceName/projects/$projectId/diagnostics",
      params: { workspaceName, projectId },
    });
  const goToClosed = () =>
    navigate({
      to: "/$workspaceName/projects/$projectId/diagnostics/resolved",
      params: { workspaceName, projectId },
    });
  const queryClient = useQueryClient();

  const AssistantSidebar = usePluginsStore((state) => state.AssistantSidebar);
  const ollieEnabled = useIsFeatureEnabled(FeatureToggleKeys.OLLIE_ENABLED);
  const agentInsightsEnabled = useIsFeatureEnabled(
    FeatureToggleKeys.AGENT_INSIGHTS_ENABLED,
  );
  const guidanceEnabled = useIsFeatureEnabled(
    FeatureToggleKeys.AGENT_INSIGHTS_GUIDANCE_ENABLED,
  );

  // Running / enabling / configuring diagnostics are write actions gated on
  // workspace-settings permission; viewing issues stays open to all.
  const {
    permissions: { canConfigureWorkspaceSettings: canConfigure },
  } = usePermissions();

  const { data: job, isPending: isJobPending } = useAgentInsightsJob({
    projectId,
  });
  const isJobEnabled = job?.status === AGENT_INSIGHTS_JOB_STATUS.enabled;

  const { data: issuesData, isPending: isStatsPending } =
    useAgentInsightsIssuesList({ projectId, page: 1, size: 100 });

  const stats = useMemo(() => {
    const issues = issuesData?.content ?? [];
    return {
      tracesAffected: countAffectedTraces(issues),
      openIssues: issues.filter(
        (i) => i.status === AGENT_INSIGHTS_ISSUE_STATUS.open,
      ).length,
      resolved: issues.filter(
        (i) => i.status === AGENT_INSIGHTS_ISSUE_STATUS.resolved,
      ).length,
    };
  }, [issuesData]);

  // Closed issues counts (size 1: only the totals are needed).
  const { data: resolvedPage } = useAgentInsightsIssuesList({
    projectId,
    status: AGENT_INSIGHTS_ISSUE_STATUS.resolved,
    page: 1,
    size: 1,
  });
  const { data: notUsefulPage } = useAgentInsightsIssuesList(
    {
      projectId,
      status: AGENT_INSIGHTS_ISSUE_STATUS.closed,
      page: 1,
      size: 1,
    },
    { enabled: guidanceEnabled },
  );
  const resolvedCount = resolvedPage?.total ?? 0;
  const notUsefulCount = guidanceEnabled ? notUsefulPage?.total ?? 0 : 0;

  const [closedTabParam, setClosedTab] = useQueryParam("tab", StringParam, {
    updateType: "replaceIn",
  });
  const closedTab =
    guidanceEnabled && closedTabParam === CLOSED_TABS.notUseful
      ? CLOSED_TABS.notUseful
      : CLOSED_TABS.resolved;

  const latestIssueUpdate = useMemo(
    () => maxUpdatedAt(issuesData?.content ?? []),
    [issuesData],
  );
  const lastScan =
    job?.last_scan_at ??
    (latestIssueUpdate > 0
      ? new Date(latestIssueUpdate).toISOString()
      : undefined);

  const { toast } = useToast();
  const triggerMutation = useTriggerAgentInsightsJobMutation();
  const { isRunning, startedAt, baseline, failBaseline, startRun, endRun } =
    useDiagnosticsRunState(projectId);
  const { markSeen } = useDiagnosticsSeen(projectId);

  // The automatic run is server-side, so no browser holds its state: it is in flight while
  // its start is newer than both the last result and the last failure. Manual runs keep
  // using the local flag.
  const autoRunAt = job?.auto_first_run_at
    ? Date.parse(job.auto_first_run_at)
    : 0;
  const isAutoRunInFlight =
    autoRunAt > 0 &&
    (job?.last_scan_at ? Date.parse(job.last_scan_at) : 0) < autoRunAt &&
    (job?.last_failed_at ? Date.parse(job.last_failed_at) : 0) < autoRunAt &&
    Date.now() - autoRunAt < AUTO_RUN_MAX_DURATION_MS;
  const showRunning = isRunning || isAutoRunInFlight;

  // Derive failure from the job (BE sets it, clears on next success) so the banner
  // is correct across reloads/tabs; the spinner takes precedence while running.
  const failedReason =
    !showRunning && job?.last_failed_at ? job.last_failure_reason : undefined;
  const failedDetail =
    !showRunning && job?.last_failed_at ? job.last_failure_detail : undefined;

  const { data: hasCredits } = useOllieCredits();
  const isOutOfCredits = hasCredits === false;

  // Stale nudge: scan older than the threshold + traces in the last 24h. Uses the
  // displayed `lastScan` fallback, and an hour-bucketed cutoff rounded up (window
  // stays <=24h) so the query key doesn't churn each render.
  const scanAt = lastScan ? Date.parse(lastScan) : 0;
  const scanIsOld = scanAt > 0 && Date.now() - scanAt > STALE_AFTER_MS;
  const last24hCutoff = new Date(
    Math.ceil(Date.now() / HOUR_MS) * HOUR_MS - DAY_MS,
  ).toISOString();
  const { data: recentTracesData } = useTracesList(
    {
      projectId,
      page: 1,
      size: 1,
      filters: [
        {
          id: "stale-recent-traces",
          field: "last_updated_at",
          type: COLUMN_TYPE.time,
          operator: ">",
          value: last24hCutoff,
        },
      ],
    },
    { enabled: scanIsOld && !showRunning },
  );
  const recentTraceCount = recentTracesData?.total ?? 0;
  const isStale = scanIsOld && !showRunning && recentTraceCount > 0;
  const staleDays = scanAt ? Math.floor((Date.now() - scanAt) / DAY_MS) : 0;

  // Eligibility gate: traces in the eligibility window, hour-bucketed like the
  // stale cutoff so the query key doesn't churn each render. Filters on created_at
  // to match the window the backend counts over.
  const eligibilityCutoff = new Date(
    Math.ceil(Date.now() / HOUR_MS) * HOUR_MS - AUTO_FIRST_RUN_WINDOW_MS,
  ).toISOString();
  const awaitsAutoFirstRun = Boolean(
    job?.auto_first_run_enrolled && !job?.auto_first_run_at,
  );
  // Hoisted so the poll below can invalidate this query alone: the key is [TRACES_KEY, params], and a
  // bare TRACES_KEY would refetch every traces query on the page.
  const eligibilityTracesParams = useMemo(
    () => ({
      projectId,
      page: 1,
      size: 1,
      filters: [
        {
          id: "diagnostics-eligibility-traces",
          field: "created_at",
          type: COLUMN_TYPE.time,
          operator: ">" as const,
          value: eligibilityCutoff,
        },
      ],
    }),
    [projectId, eligibilityCutoff],
  );
  const { data: windowTracesData, isLoading: isTraceCountPending } =
    useTracesList(eligibilityTracesParams, { enabled: awaitsAutoFirstRun });
  const windowTraceCount = windowTracesData?.total ?? 0;

  const [guidanceOpen, setGuidanceOpen] = useState(false);
  const columnsRef = useRef<HTMLDivElement>(null);
  const columnsOverflow = useColumnsOverflow(columnsRef, issuesData);

  useEffect(() => {
    if (job?.last_scan_at) markSeen(job.last_scan_at);
  }, [job?.last_scan_at, markSeen]);

  useEffect(() => {
    if (!showRunning) return;
    const id = window.setInterval(() => {
      queryClient.invalidateQueries({ queryKey: [AGENT_INSIGHTS_ISSUES_KEY] });
      queryClient.invalidateQueries({ queryKey: [AGENT_INSIGHTS_JOB_KEY] });
    }, RUN_POLL_INTERVAL_MS);
    return () => window.clearInterval(id);
  }, [showRunning, queryClient]);

  useEffect(() => {
    if (!awaitsAutoFirstRun) return;
    const id = window.setInterval(() => {
      queryClient.invalidateQueries({
        queryKey: [TRACES_KEY, eligibilityTracesParams],
      });
      queryClient.invalidateQueries({ queryKey: [AGENT_INSIGHTS_JOB_KEY] });
    }, ELIGIBILITY_POLL_INTERVAL_MS);
    return () => window.clearInterval(id);
  }, [awaitsAutoFirstRun, eligibilityTracesParams, queryClient]);

  useEffect(() => {
    if (!isRunning) return;
    if (maxUpdatedAt(issuesData?.content ?? []) > baseline) {
      trackEvent(OpikEvent.DIAGNOSTICS_RUN_COMPLETED, {
        project_id: projectId,
        duration_ms: startedAt ? Date.now() - startedAt : undefined,
      });
      endRun();
    }
  }, [issuesData, isRunning, baseline, startedAt, projectId, endRun]);

  // last_failed_at past the trigger baseline = this run failed: end it and toast once.
  useEffect(() => {
    if (!isRunning) return;
    const failedAt = job?.last_failed_at ? Date.parse(job.last_failed_at) : 0;
    if (failedAt > failBaseline) {
      trackEvent(OpikEvent.DIAGNOSTICS_RUN_FAILED, {
        project_id: projectId,
        reason: job?.last_failure_reason,
      });
      endRun();
      if (job?.last_failure_reason === OUT_OF_CREDITS_FAILURE_REASON) {
        queryClient.invalidateQueries({ queryKey: [OLLIE_CREDITS_KEY] });
      }
      const { title, description } = getRunFailureCopy(
        job?.last_failure_reason,
      );
      toast({ title, description, variant: "destructive" });
    }
  }, [job, isRunning, failBaseline, projectId, endRun, toast, queryClient]);

  const hasData = (issuesData?.content?.length ?? 0) > 0;
  const isActive = isJobEnabled || showRunning;

  const showJobControls =
    isActive || hasData || Boolean(job?.last_scan_at) || Boolean(failedReason);

  if (!AssistantSidebar || !ollieEnabled || !agentInsightsEnabled) {
    return (
      <Navigate
        to="/$workspaceName/projects/$projectId/home"
        params={{ workspaceName, projectId }}
        replace
      />
    );
  }

  const handleRunDiagnostic = () => {
    trackEvent(OpikEvent.DIAGNOSTICS_RUN_CLICKED, {
      project_id: projectId,
      source: isJobEnabled || hasData ? "rerun" : "empty_state",
      is_first_run: !hasData,
    });
    const baselineNow = maxUpdatedAt(issuesData?.content ?? []);
    const failBaselineNow = job?.last_failed_at
      ? Date.parse(job.last_failed_at)
      : 0;
    triggerMutation.mutate(
      { projectId },
      { onSuccess: () => startRun(baselineNow, failBaselineNow) },
    );
  };

  const layout = columnsOverflow ? LAYOUT.scrolling : LAYOUT.fitted;
  const headerControls = getHeaderControls({
    showClosed,
    canConfigure,
    showJobControls,
  });

  const canRun = !showRunning && !isOutOfCredits && !triggerMutation.isPending;

  // Closing an issue as not useful doesn't change the guidance version.
  const showGuidanceChanged =
    guidanceEnabled && !showRunning && isGuidanceOutdated(job);

  const closedTabs = (
    <Tabs value={closedTab} onValueChange={setClosedTab}>
      <TabsList variant="segmented-primary" className="shrink-0">
        <TabsTrigger
          value={CLOSED_TABS.resolved}
          variant="segmented-primary"
          size="sm"
          className="h-6 flex-1 gap-1"
        >
          <CircleCheck className="size-3" />
          Resolved ({resolvedCount})
        </TabsTrigger>
        {guidanceEnabled && (
          <TabsTrigger
            value={CLOSED_TABS.notUseful}
            variant="segmented-primary"
            size="sm"
            className="h-6 flex-1 gap-1"
          >
            <ThumbsDown className="size-3" />
            Not useful ({notUsefulCount})
          </TabsTrigger>
        )}
      </TabsList>
    </Tabs>
  );

  const renderBody = () => {
    if (
      !isRunning &&
      (isJobPending ||
        (!isJobEnabled && (isStatsPending || isTraceCountPending)))
    ) {
      return <SignalsPageSkeleton />;
    }

    // Nothing to show yet. Enrolled projects get progress towards the run that is coming to them;
    // everyone else gets something to click.
    if (
      !showRunning &&
      !failedReason &&
      !hasData &&
      !job?.last_scan_at &&
      (awaitsAutoFirstRun || !isJobEnabled)
    ) {
      return (
        <DiagnosticsEmptyState
          awaitsAutoFirstRun={awaitsAutoFirstRun}
          traceCount={windowTraceCount}
          isOutOfCredits={isOutOfCredits}
          canConfigure={canConfigure}
          onRun={handleRunDiagnostic}
          isRunPending={triggerMutation.isPending}
        />
      );
    }

    return (
      <div className={layout.body}>
        {!showClosed && (
          <div className="hidden lg:block">
            <SignalsStatsCards
              tracesAffected={stats.tracesAffected}
              openIssues={stats.openIssues}
              resolved={stats.resolved}
              isPending={isStatsPending}
              hasData={hasData}
            />
          </div>
        )}

        {!showClosed && showGuidanceChanged && (
          <GuidanceChangedCallout
            onRun={canConfigure && canRun ? handleRunDiagnostic : undefined}
          />
        )}

        {!showClosed && (
          <div className="flex items-center gap-2">
            {lastScan ? (
              <span className="comet-body-xs text-muted-slate">
                Last scan: {formatDate(lastScan)}
              </span>
            ) : (
              !hasData && (
                <span className="comet-body-xs text-muted-slate">
                  No runs yet
                </span>
              )
            )}
          </div>
        )}

        <div ref={columnsRef} className={layout.columns}>
          <IssuesTab
            projectId={projectId}
            status={
              !showClosed
                ? AGENT_INSIGHTS_ISSUE_STATUS.open
                : closedTab === CLOSED_TABS.notUseful
                  ? AGENT_INSIGHTS_ISSUE_STATUS.closed
                  : AGENT_INSIGHTS_ISSUE_STATUS.resolved
            }
            tabs={showClosed ? closedTabs : undefined}
            isRunning={showRunning}
            failedReason={failedReason}
            failedDetail={failedDetail}
            isStale={isStale}
            staleTraceCount={recentTraceCount}
            staleDays={staleDays}
            canConfigure={canConfigure}
            canCloseAsNotUseful={guidanceEnabled}
            closedCount={resolvedCount + notUsefulCount}
            onRunDiagnostic={canConfigure ? handleRunDiagnostic : undefined}
            onShowOpenIssues={goToOpenIssues}
            onShowClosedIssues={goToClosed}
          />
        </div>
      </div>
    );
  };

  return (
    <PageBodyScrollContainer className="flex flex-col">
      <div className="mb-4 mt-6 flex shrink-0 items-center justify-between px-6">
        {showClosed ? (
          <div className="flex min-w-0 items-center gap-2">
            <BackButton
              to="/$workspaceName/projects/$projectId/diagnostics"
              tooltip="Back to diagnostics"
            />
            <h1 className="truncate break-words text-base font-medium tracking-normal text-foreground-secondary">
              Closed issues
            </h1>
          </div>
        ) : (
          <div className="flex min-w-0 items-center gap-2">
            <h1 className="truncate break-words text-base font-medium tracking-normal text-foreground-secondary">
              Diagnostics
            </h1>
            {showJobControls && (
              <AutoRunToggle
                projectId={projectId}
                enabled={isJobEnabled}
                canConfigure={canConfigure}
              />
            )}
          </div>
        )}
        {headerControls.settings && (
          <div className="flex items-center gap-2">
            <DiagnosticsSettingsMenu
              projectId={projectId}
              enabled={isJobEnabled}
              onEditGuidance={
                guidanceEnabled ? () => setGuidanceOpen(true) : undefined
              }
            />
            {headerControls.run &&
              (isOutOfCredits && !showRunning ? (
                <OutOfCreditsButton
                  label="Out of Ollie credits"
                  description="Diagnostics run on your organization's Ollie credits, and there aren't enough left for another run. Issues already found stay available."
                />
              ) : (
                <Button
                  size="2xs"
                  disabled={showRunning || triggerMutation.isPending}
                  onClick={handleRunDiagnostic}
                >
                  <Play className="mr-1.5 size-3" />
                  Run diagnostic
                </Button>
              ))}
          </div>
        )}
      </div>
      {renderBody()}
      {guidanceEnabled && canConfigure && (
        <GuidanceSheet
          open={guidanceOpen}
          setOpen={setGuidanceOpen}
          projectId={projectId}
          job={job}
          onRun={canRun ? handleRunDiagnostic : undefined}
        />
      )}
    </PageBodyScrollContainer>
  );
};

export default SignalsPage;
