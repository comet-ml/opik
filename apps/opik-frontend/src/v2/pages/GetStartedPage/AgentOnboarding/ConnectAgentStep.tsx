import React, { useState } from "react";
import { ArrowRight, ChevronsRight, MonitorPlay, Undo2 } from "lucide-react";
import { Button } from "@/ui/button";
import { Tabs, TabsList, TabsTrigger, TabsContent } from "@/ui/tabs";
import Slack from "@/icons/slack.svg?react";
import useProjectByName from "@/api/projects/useProjectByName";
import useTracesList from "@/api/traces/useTracesList";
import {
  useAgentOnboarding,
  AGENT_ONBOARDING_STEPS,
} from "./AgentOnboardingContext";
import AgentOnboardingCard from "./AgentOnboardingCard";
import InstallWithAITab from "./InstallWithAITab";
import ManualIntegrationList from "./ManualIntegrationList";
import ManualIntegrationDetail from "./ManualIntegrationDetail";
import { INTEGRATIONS } from "@/constants/integrations";
import {
  SLACK_LINK,
  VIDEO_TUTORIAL_LINK,
} from "@/v2/pages-shared/onboarding/IntegrationExplorer/components/HelpLinks";

const TRACE_POLL_INTERVAL = 5000;

const ConnectAgentStep: React.FC = () => {
  const { goToStep, agentName } = useAgentOnboarding();
  const [activeTab, setActiveTab] = useState("install-with-ai");
  const [selectedIntegrationId, setSelectedIntegrationId] = useState<
    string | null
  >(null);

  const { data: project } = useProjectByName(
    { projectName: agentName },
    { enabled: !!agentName },
  );
  const projectId = project?.id;

  const { data: tracesData } = useTracesList(
    {
      projectId: projectId ?? "",
      page: 1,
      size: 1,
      sorting: [{ id: "created_at", desc: false }],
    },
    {
      enabled: !!projectId,
      refetchInterval: (query) =>
        query.state.data?.total ? false : TRACE_POLL_INTERVAL,
    },
  );

  const firstTraceId = tracesData?.content?.[0]?.id;
  const traceReceived = !!firstTraceId;

  const handleViewTraces = () => {
    goToStep(AGENT_ONBOARDING_STEPS.DONE, {
      agentName,
      traceId: firstTraceId,
    });
  };

  const selectedIntegration = selectedIntegrationId
    ? INTEGRATIONS.find((i) => i.id === selectedIntegrationId)
    : undefined;

  const handleTabChange = (value: string) => {
    setActiveTab(value);
    setSelectedIntegrationId(null);
  };

  const handleSkip = () => {
    goToStep(AGENT_ONBOARDING_STEPS.DONE, { agentName });
  };

  if (selectedIntegration) {
    return (
      <AgentOnboardingCard
        title=""
        headerContent={
          <Button
            variant="outline"
            size="xs"
            onClick={() => setSelectedIntegrationId(null)}
            className="w-fit"
            id="onboarding-integration-back"
            data-fs-element="OnboardingIntegrationBack"
          >
            <Undo2 className="mr-1 size-3" />
            Back to integrations
          </Button>
        }
        showFooterSeparator
        footer={
          <div className="flex w-full flex-col gap-3">
            <div className="flex flex-col gap-1">
              <span className="comet-body-xs-accented">Need some help?</span>
              <span className="comet-body-xs text-muted-slate">
                Get help from your team or ours. Choose the option that works
                best for you.
              </span>
            </div>
            <div className="flex gap-2.5">
              <Button
                variant="outline"
                size="2xs"
                className="flex-1"
                asChild
                id="onboarding-slack"
                data-fs-element="OnboardingSlack"
              >
                <a href={SLACK_LINK} target="_blank" rel="noopener noreferrer">
                  <Slack className="mr-1.5 size-3" />
                  Get help in Slack
                </a>
              </Button>
              <Button
                variant="outline"
                size="2xs"
                className="flex-1"
                asChild
                id="onboarding-watch-tutorial"
                data-fs-element="OnboardingWatchTutorial"
              >
                <a
                  href={VIDEO_TUTORIAL_LINK}
                  target="_blank"
                  rel="noopener noreferrer"
                >
                  <MonitorPlay className="mr-1.5 size-3" />
                  Watch our tutorial
                </a>
              </Button>
            </div>
          </div>
        }
      >
        <ManualIntegrationDetail integration={selectedIntegration} />
      </AgentOnboardingCard>
    );
  }

  return (
    <AgentOnboardingCard
      title={`Connect ${agentName} to Opik`}
      description="Follow these steps to start sending traces to Opik."
      showFooterSeparator
      footer={
        traceReceived ? (
          <Button
            onClick={handleViewTraces}
            id="onboarding-step2-view-traces"
            data-fs-element="onboarding-step2-view-traces"
          >
            View traces & start optimizing
            <ArrowRight className="size-3.5" />
          </Button>
        ) : (
          <Button
            variant="link"
            onClick={handleSkip}
            className="comet-body-s px-0 text-muted-slate"
            id="onboarding-step2-skip"
            data-fs-element="onboarding-step2-skip"
          >
            Skip for now
            <ChevronsRight className="size-3.5" />
          </Button>
        )
      }
    >
      <Tabs value={activeTab} onValueChange={handleTabChange}>
        <TabsList variant="underline">
          <TabsTrigger value="install-with-ai" variant="underline">
            Install with AI
          </TabsTrigger>
          <TabsTrigger value="manual-integration" variant="underline">
            Manual integration
          </TabsTrigger>
        </TabsList>

        <TabsContent value="install-with-ai">
          <InstallWithAITab traceReceived={traceReceived} />
        </TabsContent>

        <TabsContent value="manual-integration">
          <ManualIntegrationList
            onSelectIntegration={setSelectedIntegrationId}
          />
        </TabsContent>
      </Tabs>
    </AgentOnboardingCard>
  );
};

export default ConnectAgentStep;
