import React, { useMemo, useState } from "react";
import { Navigate, useParams } from "@tanstack/react-router";
import { BookCheck, Radar } from "lucide-react";
import { useActiveProjectId } from "@/store/AppStore";
import usePluginsStore from "@/store/PluginsStore";
import { useIsFeatureEnabled } from "@/contexts/feature-toggles-provider";
import { FeatureToggleKeys } from "@/types/feature-toggles";
import PageBodyScrollContainer from "@/v2/layout/PageBodyScrollContainer/PageBodyScrollContainer";
import PageBodyStickyContainer from "@/shared/PageBodyStickyContainer/PageBodyStickyContainer";
import { Button } from "@/ui/button";
import { Separator } from "@/ui/separator";
import { AGENT_INSIGHTS_ISSUE_STATUS } from "@/types/signals";
import useAgentInsightsIssuesList from "@/api/signals/useAgentInsightsIssuesList";
import useTriggerAgentInsightsJobMutation from "@/api/signals/useTriggerAgentInsightsJobMutation";
import SignalsStatsCards from "@/v2/pages/SignalsPage/SignalsStatsCards";
import IssuesTab from "@/v2/pages/SignalsPage/IssuesTab/IssuesTab";

const SignalsPage: React.FC = () => {
  const projectId = useActiveProjectId()!;
  const { workspaceName } = useParams({ strict: false }) as {
    workspaceName: string;
  };

  // Diagnostics is powered by the Ollie assistant and surfaces Agent Insights,
  // so it requires both the Ollie plugin/toggle and the Agent Insights toggle.
  const AssistantSidebar = usePluginsStore((state) => state.AssistantSidebar);
  const ollieEnabled = useIsFeatureEnabled(FeatureToggleKeys.OLLIE_ENABLED);
  const agentInsightsEnabled = useIsFeatureEnabled(
    FeatureToggleKeys.AGENT_INSIGHTS_ENABLED,
  );

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

  // Default view shows open issues; toggling shows resolved ones.
  const [showResolved, setShowResolved] = useState(false);

  if (!AssistantSidebar || !ollieEnabled || !agentInsightsEnabled) {
    return (
      <Navigate
        to="/$workspaceName/projects/$projectId/home"
        params={{ workspaceName, projectId }}
        replace
      />
    );
  }

  return (
    <PageBodyScrollContainer>
      <PageBodyStickyContainer
        className="mb-4 mt-6 flex items-center justify-between"
        direction="horizontal"
      >
        <h1 className="comet-title-l truncate break-words">Diagnostics</h1>
      </PageBodyStickyContainer>

      <div className="flex flex-col gap-4 px-6 pb-6">
        <SignalsStatsCards
          tracesAffected={stats.tracesAffected}
          openIssues={stats.openIssues}
          resolved={stats.resolved}
          isPending={isStatsPending}
        />

        <div className="flex items-center justify-end gap-2">
          <Button
            variant="outline"
            size="xs"
            disabled={triggerMutation.isPending}
            onClick={() => triggerMutation.mutate({ projectId })}
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

        <IssuesTab projectId={projectId} showResolved={showResolved} />
      </div>
    </PageBodyScrollContainer>
  );
};

export default SignalsPage;
