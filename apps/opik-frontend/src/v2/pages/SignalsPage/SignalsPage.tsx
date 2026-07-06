import React, { useEffect, useMemo, useState } from "react";
import { Navigate, useNavigate, useParams } from "@tanstack/react-router";
import { useQueryClient } from "@tanstack/react-query";
import { BookOpenCheck, Radar, Settings2 } from "lucide-react";
import { useActiveProjectId } from "@/store/AppStore";
import usePluginsStore from "@/store/PluginsStore";
import { useIsFeatureEnabled } from "@/contexts/feature-toggles-provider";
import { usePermissions } from "@/contexts/PermissionsContext";
import { FeatureToggleKeys } from "@/types/feature-toggles";
import { AGENT_INSIGHTS_ISSUES_KEY, AGENT_INSIGHTS_JOB_KEY } from "@/api/api";
import { formatDate } from "@/lib/date";
import PageBodyScrollContainer from "@/v2/layout/PageBodyScrollContainer/PageBodyScrollContainer";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import BackButton from "@/shared/BackButton/BackButton";
import { Button } from "@/ui/button";
import { Separator } from "@/ui/separator";
import {
  AGENT_INSIGHTS_ISSUE_STATUS,
  AGENT_INSIGHTS_JOB_STATUS,
  AgentInsightsIssue,
} from "@/types/signals";
import useAgentInsightsIssuesList from "@/api/signals/useAgentInsightsIssuesList";
import useTracesList from "@/api/traces/useTracesList";
import { COLUMN_TYPE } from "@/types/shared";
import useAgentInsightsJob from "@/api/signals/useAgentInsightsJob";
import useTriggerAgentInsightsJobMutation from "@/api/signals/useTriggerAgentInsightsJobMutation";
import useUpdateAgentInsightsJobMutation from "@/api/signals/useUpdateAgentInsightsJobMutation";
import useDiagnosticsRunState from "@/hooks/useDiagnosticsRunState";
import useDiagnosticsSeen from "@/hooks/useDiagnosticsSeen";
import { getRunFailureCopy } from "@/v2/pages/SignalsPage/runFailureCopy";
import { OpikEvent, trackEvent } from "@/lib/analytics/tracking";
import { useToast } from "@/ui/use-toast";
import SignalsStatsCards from "@/v2/pages/SignalsPage/SignalsStatsCards";
import IssuesTab from "@/v2/pages/SignalsPage/IssuesTab/IssuesTab";
import DiagnosticsEmptyState from "@/v2/pages/SignalsPage/DiagnosticsEmptyState";
import DiagnosticsSettingsDialog from "@/v2/pages/SignalsPage/DiagnosticsSettingsDialog";
import SignalsPageSkeleton from "@/v2/pages/SignalsPage/SignalsPageSkeleton";

const RUN_POLL_INTERVAL_MS = 8000;
const HOUR_MS = 60 * 60 * 1000;
const DAY_MS = 24 * HOUR_MS;
const STALE_AFTER_MS = 3 * DAY_MS;

const maxUpdatedAt = (issues: AgentInsightsIssue[]): number =>
  issues.reduce((max, issue) => {
    const t = issue.last_updated_at ? Date.parse(issue.last_updated_at) : 0;
    return Number.isFinite(t) && t > max ? t : max;
  }, 0);

const SignalsPage: React.FC<{ showResolved?: boolean }> = ({
  showResolved = false,
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
  const goToResolved = () =>
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
      tracesAffected: issues.reduce((sum, i) => sum + i.total_occurrences, 0),
      openIssues: issues.filter(
        (i) => i.status === AGENT_INSIGHTS_ISSUE_STATUS.open,
      ).length,
      resolved: issues.filter(
        (i) => i.status === AGENT_INSIGHTS_ISSUE_STATUS.resolved,
      ).length,
    };
  }, [issuesData]);

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
  const updateJobMutation = useUpdateAgentInsightsJobMutation();
  const { isRunning, startedAt, baseline, failBaseline, startRun, endRun } =
    useDiagnosticsRunState(projectId);
  const { markSeen } = useDiagnosticsSeen(projectId);

  // Derive failure from the job (BE sets it, clears on next success) so the banner
  // is correct across reloads/tabs; the spinner takes precedence while running.
  const failedReason =
    !isRunning && job?.last_failed_at ? job.last_failure_reason : undefined;
  const failedDetail =
    !isRunning && job?.last_failed_at ? job.last_failure_detail : undefined;

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
    { enabled: scanIsOld && !isRunning },
  );
  const recentTraceCount = recentTracesData?.total ?? 0;
  const isStale = scanIsOld && !isRunning && recentTraceCount > 0;
  const staleDays = scanAt ? Math.floor((Date.now() - scanAt) / DAY_MS) : 0;

  const [settingsOpen, setSettingsOpen] = useState(false);

  useEffect(() => {
    if (job?.last_scan_at) markSeen(job.last_scan_at);
  }, [job?.last_scan_at, markSeen]);

  useEffect(() => {
    if (!isRunning) return;
    const id = window.setInterval(() => {
      queryClient.invalidateQueries({ queryKey: [AGENT_INSIGHTS_ISSUES_KEY] });
      queryClient.invalidateQueries({ queryKey: [AGENT_INSIGHTS_JOB_KEY] });
    }, RUN_POLL_INTERVAL_MS);
    return () => window.clearInterval(id);
  }, [isRunning, queryClient]);

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
      const { title, description } = getRunFailureCopy(
        job?.last_failure_reason,
      );
      toast({ title, description, variant: "destructive" });
    }
  }, [job, isRunning, failBaseline, projectId, endRun, toast]);

  const hasData = (issuesData?.content?.length ?? 0) > 0;
  const isActive = isJobEnabled || isRunning;

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

  const handleTurnOnAuto = () => {
    trackEvent(OpikEvent.DIAGNOSTICS_AUTO_ENABLED, {
      project_id: projectId,
      source: "header",
    });
    updateJobMutation.mutate({
      projectId,
      status: AGENT_INSIGHTS_JOB_STATUS.enabled,
    });
  };

  const renderBody = () => {
    if (!isRunning && (isJobPending || (!isJobEnabled && isStatsPending))) {
      return <SignalsPageSkeleton />;
    }

    if (!isRunning && !failedReason && !isJobEnabled && !hasData) {
      return (
        <DiagnosticsEmptyState
          onRun={handleRunDiagnostic}
          isPending={triggerMutation.isPending}
          canConfigure={canConfigure}
        />
      );
    }

    return (
      <div className="flex min-h-0 flex-1 flex-col gap-4 px-6 pb-3">
        {!showResolved && (
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

        {!showResolved && (
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
            <div className="ml-auto flex items-center gap-2">
              {canConfigure && (
                <>
                  <Button
                    variant="outline"
                    size="xs"
                    disabled={isRunning || triggerMutation.isPending}
                    onClick={handleRunDiagnostic}
                  >
                    <Radar className="mr-1.5 size-3.5" />
                    Run diagnostic
                  </Button>
                  <Separator orientation="vertical" className="h-5" />
                </>
              )}
              <TooltipWrapper content="Resolved issues">
                <Button
                  variant="outline"
                  size="xs"
                  onClick={goToResolved}
                  aria-label="Resolved issues"
                >
                  <BookOpenCheck className="size-3.5 lg:mr-1.5" />
                  <span className="hidden lg:inline">Resolved issues</span>
                </Button>
              </TooltipWrapper>
            </div>
          </div>
        )}

        <IssuesTab
          projectId={projectId}
          showResolved={showResolved}
          isRunning={isRunning}
          failedReason={failedReason}
          failedDetail={failedDetail}
          isStale={isStale}
          staleTraceCount={recentTraceCount}
          staleDays={staleDays}
          canConfigure={canConfigure}
          onRunDiagnostic={canConfigure ? handleRunDiagnostic : undefined}
          onShowOpenIssues={goToOpenIssues}
        />
      </div>
    );
  };

  return (
    <PageBodyScrollContainer className="flex flex-col overflow-hidden">
      <div className="mb-4 mt-6 flex shrink-0 items-center justify-between px-6">
        {showResolved ? (
          <div className="flex min-w-0 items-center gap-2">
            <BackButton
              to="/$workspaceName/projects/$projectId/diagnostics"
              tooltip="Back to diagnostics"
            />
            <h1 className="truncate break-words text-base font-medium tracking-normal text-foreground-secondary">
              Resolved issues
            </h1>
          </div>
        ) : (
          <h1 className="truncate break-words text-base font-medium tracking-normal text-foreground-secondary">
            Diagnostics
          </h1>
        )}
        {!showResolved && (isActive || hasData) && (
          <div className="flex items-center gap-1.5">
            <span className="comet-body-xs flex items-center gap-1.5 text-foreground-secondary">
              <span
                className={`size-2 rounded-full ${
                  isJobEnabled ? "bg-[var(--color-emerald)]" : "bg-chart-red"
                }`}
              />
              {isJobEnabled ? "Auto • Daily" : "Manual"}
            </span>
            {canConfigure &&
              (isJobEnabled ? (
                <TooltipWrapper content="Settings">
                  <Button
                    variant="ghost"
                    size="icon-2xs"
                    onClick={() => setSettingsOpen(true)}
                    aria-label="Diagnostics settings"
                    className="text-foreground"
                  >
                    <Settings2 className="size-3" />
                  </Button>
                </TooltipWrapper>
              ) : (
                <Button
                  size="xs"
                  onClick={handleTurnOnAuto}
                  disabled={updateJobMutation.isPending}
                  className="ml-1.5"
                >
                  Turn on auto-diagnostic
                </Button>
              ))}
          </div>
        )}
      </div>
      {renderBody()}
      <DiagnosticsSettingsDialog
        open={settingsOpen}
        setOpen={setSettingsOpen}
        projectId={projectId}
        enabled={isJobEnabled}
      />
    </PageBodyScrollContainer>
  );
};

export default SignalsPage;
