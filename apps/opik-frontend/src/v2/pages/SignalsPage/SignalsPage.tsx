import React, { useEffect, useMemo, useState } from "react";
import { Navigate, useParams } from "@tanstack/react-router";
import { useQueryClient } from "@tanstack/react-query";
import { BookCheck, Radar } from "lucide-react";
import { useActiveProjectId } from "@/store/AppStore";
import usePluginsStore from "@/store/PluginsStore";
import { useIsFeatureEnabled } from "@/contexts/feature-toggles-provider";
import { FeatureToggleKeys } from "@/types/feature-toggles";
import { AGENT_INSIGHTS_ISSUES_KEY } from "@/api/api";
import PageBodyScrollContainer from "@/v2/layout/PageBodyScrollContainer/PageBodyScrollContainer";
import PageBodyStickyContainer from "@/shared/PageBodyStickyContainer/PageBodyStickyContainer";
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
import useDiagnosticsRunState from "@/v2/pages/SignalsPage/useDiagnosticsRunState";
import SignalsStatsCards from "@/v2/pages/SignalsPage/SignalsStatsCards";
import IssuesTab from "@/v2/pages/SignalsPage/IssuesTab/IssuesTab";
import DiagnosticsEmptyState from "@/v2/pages/SignalsPage/DiagnosticsEmptyState";
import Loader from "@/shared/Loader/Loader";

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

  const triggerMutation = useTriggerAgentInsightsJobMutation();
  const { isRunning, baseline, startRun, endRun } =
    useDiagnosticsRunState(projectId);

  // Default view shows open issues; toggling shows resolved ones.
  const [showResolved, setShowResolved] = useState(false);

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

  const renderBody = () => {
    // A persisted in-flight run takes precedence so we land straight on the
    // issues view (with the running banner) instead of flashing the empty state.
    if (!isRunning && isJobPending) {
      return <Loader />;
    }

    // No job yet, or it was turned off → onboarding. "Run first diagnostic"
    // creates+enables the job and triggers a run; this page then swaps to the
    // issues view below.
    if (!isRunning && !isJobEnabled) {
      return (
        <DiagnosticsEmptyState
          onRun={handleRunDiagnostic}
          isPending={triggerMutation.isPending}
        />
      );
    }

    const hasData = (issuesData?.content?.length ?? 0) > 0;

    return (
      <div className="flex flex-col gap-4 px-6 pb-6">
        {/* Stats are only meaningful once a run has produced issues. */}
        {hasData && (
          <SignalsStatsCards
            tracesAffected={stats.tracesAffected}
            openIssues={stats.openIssues}
            resolved={stats.resolved}
            isPending={isStatsPending}
            hasData={hasData}
          />
        )}

        <div className="flex items-center justify-end gap-2">
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
          <Button
            variant={showResolved ? "secondary" : "outline"}
            size="xs"
            onClick={() => setShowResolved((v) => !v)}
          >
            <BookCheck className="mr-1.5 size-3.5" />
            Resolved issues
          </Button>
        </div>

        <IssuesTab
          projectId={projectId}
          showResolved={showResolved}
          isRunning={isRunning}
          onRunDiagnostic={handleRunDiagnostic}
        />
      </div>
    );
  };

  return (
    <PageBodyScrollContainer>
      <PageBodyStickyContainer
        className="mb-4 mt-6 flex items-center justify-between"
        direction="horizontal"
      >
        <h1 className="comet-title-l truncate break-words">Diagnostics</h1>
        {(isJobEnabled || isRunning) && (
          <span className="comet-body-xs flex items-center gap-1.5 text-muted-slate">
            <span className="size-1.5 rounded-full bg-emerald-400" />
            Active
          </span>
        )}
      </PageBodyStickyContainer>
      {renderBody()}
    </PageBodyScrollContainer>
  );
};

export default SignalsPage;
