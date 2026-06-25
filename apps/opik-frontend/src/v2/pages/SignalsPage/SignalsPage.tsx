import React, { useEffect, useMemo, useState } from "react";
import { Navigate, useParams } from "@tanstack/react-router";
import { useQueryClient } from "@tanstack/react-query";
import { ArrowLeft, BookOpenCheck, Radar, Settings2 } from "lucide-react";
import { useActiveProjectId } from "@/store/AppStore";
import usePluginsStore from "@/store/PluginsStore";
import { useIsFeatureEnabled } from "@/contexts/feature-toggles-provider";
import { FeatureToggleKeys } from "@/types/feature-toggles";
import { AGENT_INSIGHTS_ISSUES_KEY } from "@/api/api";
import { formatDate } from "@/lib/date";
import PageBodyScrollContainer from "@/v2/layout/PageBodyScrollContainer/PageBodyScrollContainer";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import { Button } from "@/ui/button";
import { Separator } from "@/ui/separator";
import {
  AGENT_INSIGHTS_ISSUE_STATUS,
  AGENT_INSIGHTS_JOB_STATUS,
  AgentInsightsIssue,
} from "@/types/signals";
import useAgentInsightsIssuesList from "@/api/signals/useAgentInsightsIssuesList";
import useAgentInsightsJob from "@/api/signals/useAgentInsightsJob";
import useTriggerAgentInsightsJobMutation from "@/api/signals/useTriggerAgentInsightsJobMutation";
import useUpdateAgentInsightsJobMutation from "@/api/signals/useUpdateAgentInsightsJobMutation";
import useDiagnosticsRunState from "@/v2/pages/SignalsPage/useDiagnosticsRunState";
import SignalsStatsCards from "@/v2/pages/SignalsPage/SignalsStatsCards";
import IssuesTab from "@/v2/pages/SignalsPage/IssuesTab/IssuesTab";
import DiagnosticsEmptyState from "@/v2/pages/SignalsPage/DiagnosticsEmptyState";
import DiagnosticsSettingsDialog from "@/v2/pages/SignalsPage/DiagnosticsSettingsDialog";
import SignalsPageSkeleton from "@/v2/pages/SignalsPage/SignalsPageSkeleton";

// While a run is in flight we poll the issues queries on this cadence.
const RUN_POLL_INTERVAL_MS = 8000;

// Newest issue update time (server epoch ms) across the list; 0 when empty.
const maxUpdatedAt = (issues: AgentInsightsIssue[]): number =>
  issues.reduce((max, issue) => {
    const t = issue.last_updated_at ? Date.parse(issue.last_updated_at) : 0;
    return Number.isFinite(t) && t > max ? t : max;
  }, 0);

const SignalsPage: React.FC = () => {
  const projectId = useActiveProjectId()!;
  const { workspaceName } = useParams({ strict: false }) as {
    workspaceName: string;
  };
  const queryClient = useQueryClient();

  // Diagnostics is powered by the Ollie assistant and surfaces Agent Insights,
  // so it requires both the Ollie plugin/toggle and the Agent Insights toggle.
  const AssistantSidebar = usePluginsStore((state) => state.AssistantSidebar);
  const ollieEnabled = useIsFeatureEnabled(FeatureToggleKeys.OLLIE_ENABLED);
  const agentInsightsEnabled = useIsFeatureEnabled(
    FeatureToggleKeys.AGENT_INSIGHTS_ENABLED,
  );

  // The per-project job drives onboarding: no job (404 → null) or a disabled
  // job shows the empty state; an enabled job shows the issues view.
  const { data: job, isPending: isJobPending } = useAgentInsightsJob({
    projectId,
  });
  const isJobEnabled = job?.status === AGENT_INSIGHTS_JOB_STATUS.enabled;

  // No dedicated stats endpoint yet — derive the header metrics from the issues
  // list (all statuses) until the backend exposes aggregates.
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

  // Last scan = when the diagnostic last produced a report (server-stamped on
  // every run, incl. all-clear; not bumped by resolving/reopening issues).
  // Fall back to the newest issue update when last_scan_at is unset but issues
  // exist — a scan clearly ran, so we still surface a timestamp (e.g. once all
  // issues are resolved) instead of dropping the line.
  const latestIssueUpdate = useMemo(
    () => maxUpdatedAt(issuesData?.content ?? []),
    [issuesData],
  );
  const lastScan =
    job?.last_scan_at ??
    (latestIssueUpdate > 0
      ? new Date(latestIssueUpdate).toISOString()
      : undefined);

  const triggerMutation = useTriggerAgentInsightsJobMutation();
  const updateJobMutation = useUpdateAgentInsightsJobMutation();
  const { isRunning, baseline, startRun, endRun } =
    useDiagnosticsRunState(projectId);

  // Default view shows open issues; toggling shows resolved ones.
  const [showResolved, setShowResolved] = useState(false);
  const [settingsOpen, setSettingsOpen] = useState(false);

  // While running, refresh the issues queries (stats here + the IssuesTab list)
  // so freshly persisted results surface without a manual reload.
  useEffect(() => {
    if (!isRunning) return;
    const id = window.setInterval(() => {
      queryClient.invalidateQueries({ queryKey: [AGENT_INSIGHTS_ISSUES_KEY] });
    }, RUN_POLL_INTERVAL_MS);
    return () => window.clearInterval(id);
  }, [isRunning, queryClient]);

  // The run reports by upserting issues, which bumps their `last_updated_at`.
  // Once the newest update passes the baseline captured at trigger, it's done.
  useEffect(() => {
    if (!isRunning) return;
    if (maxUpdatedAt(issuesData?.content ?? []) > baseline) {
      endRun();
    }
  }, [issuesData, isRunning, baseline, endRun]);

  // hasData = a prior diagnostic has produced issues. isActive = the daily run
  // is on (or currently running) vs. paused.
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
    const baselineNow = maxUpdatedAt(issuesData?.content ?? []);
    triggerMutation.mutate(
      { projectId },
      { onSuccess: () => startRun(baselineNow) },
    );
  };

  const handleTurnOnAuto = () => {
    updateJobMutation.mutate({
      projectId,
      status: AGENT_INSIGHTS_JOB_STATUS.enabled,
    });
  };

  const renderBody = () => {
    // A persisted in-flight run takes precedence so we land straight on the
    // issues view (with the running banner) instead of flashing the empty state.
    if (!isRunning && (isJobPending || (!isJobEnabled && isStatsPending))) {
      return <SignalsPageSkeleton />;
    }

    // Onboarding shows only when diagnostics is off AND nothing has been
    // diagnosed yet. A disabled job with prior results keeps the issues view,
    // so past findings stay browsable while the daily run is paused.
    if (!isRunning && !isJobEnabled && !hasData) {
      return (
        <DiagnosticsEmptyState
          onRun={handleRunDiagnostic}
          isPending={triggerMutation.isPending}
        />
      );
    }

    return (
      <div className="flex min-h-0 flex-1 flex-col gap-4 px-6 pb-3">
        {/* Stats summarize open findings, so they're hidden in the resolved
            view and skipped on compact screens (the list collapses there).
            Otherwise shown; cards render "-" until a run produces data. */}
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
              // Only claim "No runs yet" when there's genuinely no data; if issues
              // exist a run clearly happened (last_scan_at may just be unset).
              !hasData && (
                <span className="comet-body-xs text-muted-slate">
                  No runs yet
                </span>
              )
            )}
            <div className="ml-auto flex items-center gap-2">
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
              {/* Compact screens: icon-only with the label on hover. */}
              <TooltipWrapper content="Resolved issues">
                <Button
                  variant="outline"
                  size="xs"
                  onClick={() => setShowResolved(true)}
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
          onRunDiagnostic={handleRunDiagnostic}
          onShowOpenIssues={() => setShowResolved(false)}
        />
      </div>
    );
  };

  return (
    <PageBodyScrollContainer className="flex flex-col overflow-hidden">
      {/* Chrome stays fixed at the top; the two-pane below fills the remaining
          height and each column scrolls independently (no page-level scroll). */}
      <div className="mb-4 mt-6 flex shrink-0 items-center justify-between px-6">
        {showResolved ? (
          // Resolved view: the back arrow returns to the open-issues view, and
          // the title names the view (status chrome is hidden here).
          <Button
            variant="ghost"
            onClick={() => setShowResolved(false)}
            aria-label="Back to diagnostics"
            className="h-auto min-w-0 gap-2 px-0 text-foreground-secondary"
          >
            <ArrowLeft className="size-4 shrink-0" />
            <h1 className="truncate break-words text-base font-medium tracking-normal">
              Resolved issues
            </h1>
          </Button>
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
                  isJobEnabled ? "bg-emerald-400" : "bg-chart-red"
                }`}
              />
              {isJobEnabled ? "Auto • Daily" : "Manual"}
            </span>
            {isJobEnabled ? (
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
            )}
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
