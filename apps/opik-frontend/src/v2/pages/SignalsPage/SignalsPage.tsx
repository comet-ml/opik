import React, { useEffect, useMemo, useState } from "react";
import { Navigate, useParams } from "@tanstack/react-router";
import { useQueryClient } from "@tanstack/react-query";
import { ArrowLeft, BookOpenCheck, Radar, Settings2 } from "lucide-react";
import { useActiveProjectId } from "@/store/AppStore";
import usePluginsStore from "@/store/PluginsStore";
import { useIsFeatureEnabled } from "@/contexts/feature-toggles-provider";
import { FeatureToggleKeys } from "@/types/feature-toggles";
import { AGENT_INSIGHTS_ISSUES_KEY, AGENT_INSIGHTS_JOB_KEY } from "@/api/api";
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
import useDiagnosticsSeen from "@/v2/pages/SignalsPage/useDiagnosticsSeen";
import SignalsStatsCards from "@/v2/pages/SignalsPage/SignalsStatsCards";
import IssuesTab from "@/v2/pages/SignalsPage/IssuesTab/IssuesTab";
import DiagnosticsEmptyState from "@/v2/pages/SignalsPage/DiagnosticsEmptyState";
import DiagnosticsSettingsDialog from "@/v2/pages/SignalsPage/DiagnosticsSettingsDialog";
import SignalsPageSkeleton from "@/v2/pages/SignalsPage/SignalsPageSkeleton";

const RUN_POLL_INTERVAL_MS = 8000;

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

  const AssistantSidebar = usePluginsStore((state) => state.AssistantSidebar);
  const ollieEnabled = useIsFeatureEnabled(FeatureToggleKeys.OLLIE_ENABLED);
  const agentInsightsEnabled = useIsFeatureEnabled(
    FeatureToggleKeys.AGENT_INSIGHTS_ENABLED,
  );

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

  const triggerMutation = useTriggerAgentInsightsJobMutation();
  const updateJobMutation = useUpdateAgentInsightsJobMutation();
  const { isRunning, baseline, startRun, endRun } =
    useDiagnosticsRunState(projectId);
  const { markSeen } = useDiagnosticsSeen(projectId);

  const [showResolved, setShowResolved] = useState(false);
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
      endRun();
    }
  }, [issuesData, isRunning, baseline, endRun]);

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
    if (!isRunning && (isJobPending || (!isJobEnabled && isStatsPending))) {
      return <SignalsPageSkeleton />;
    }

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
      <div className="mb-4 mt-6 flex shrink-0 items-center justify-between px-6">
        {showResolved ? (
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
