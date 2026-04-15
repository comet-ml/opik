import React, { useEffect, useState } from "react";
import useLocalStorageState from "use-local-storage-state";
import { ArrowRight, ChevronsRight, MonitorPlay, Undo2 } from "lucide-react";
import { Button } from "@/ui/button";
import { Tabs, TabsList, TabsTrigger, TabsContent } from "@/ui/tabs";
import Slack from "@/icons/slack.svg?react";
import usePluginsStore from "@/store/PluginsStore";
import { useUserApiKey } from "@/store/AppStore";
import useProjectByName from "@/api/projects/useProjectByName";
import useTracesList from "@/api/traces/useTracesList";
import useSandboxConnectionStatus from "@/api/agent-sandbox/useSandboxConnectionStatus";
import { RunnerConnectionStatus } from "@/types/agent-sandbox";
import { OpikEvent, trackEvent } from "@/lib/analytics/tracking";
import {
  useAgentOnboarding,
  AGENT_ONBOARDING_STEPS,
  TRACES_OLDEST_FIRST_SORTING,
} from "./AgentOnboardingContext";
import AgentOnboardingCard from "./AgentOnboardingCard";
import ConnectToOllieTab from "./ConnectToOllieTab";
import InstallWithAITab from "./InstallWithAITab";
import ManualIntegrationList from "./ManualIntegrationList";
import ManualIntegrationDetail from "./ManualIntegrationDetail";
import { INTEGRATIONS } from "@/constants/integrations";
import {
  SLACK_LINK,
  VIDEO_TUTORIAL_LINK,
} from "@/v2/pages-shared/onboarding/IntegrationExplorer/components/HelpLinks";

const TRACE_POLL_INTERVAL = 5000;
const FIRST_TRACE_TRACKED_KEY = "agent-onboarding-first-trace-tracked";

const ConnectAgentStep: React.FC = () => {
  const { goToStep, agentName } = useAgentOnboarding();
  const InviteDevButton = usePluginsStore((state) => state.InviteDevButton);
  const apiKey = useUserApiKey();
  const showOllieTab = !!apiKey;
  const [activeTab, setActiveTab] = useState(
    showOllieTab ? "connect-to-ollie" : "install-with-ai",
  );
  const [manualCategory, setManualCategory] = useState<string | null>(null);
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
      sorting: TRACES_OLDEST_FIRST_SORTING,
    },
    {
      enabled: !!projectId,
      refetchInterval: (query) =>
        query.state.data?.total ? false : TRACE_POLL_INTERVAL,
    },
  );

  const firstTraceId = tracesData?.content?.[0]?.id;
  const traceReceived = !!firstTraceId;

  const [trackedTraceId, setTrackedTraceId] = useLocalStorageState<
    string | null
  >(FIRST_TRACE_TRACKED_KEY, { defaultValue: null });

  useEffect(() => {
    if (firstTraceId && firstTraceId !== trackedTraceId) {
      trackEvent(OpikEvent.ONBOARDING_FIRST_TRACE_RECEIVED, {
        agent_name: agentName,
        trace_id: firstTraceId,
      });
      setTrackedTraceId(firstTraceId);
    }
  }, [firstTraceId, trackedTraceId, agentName, setTrackedTraceId]);

  const { data: runner } = useSandboxConnectionStatus(
    { projectId: projectId ?? "", runnerType: "connect" },
    { enabled: !!projectId && activeTab === "connect-to-ollie" },
  );
  const connected = runner?.status === RunnerConnectionStatus.CONNECTED;

  const isOllieTab = activeTab === "connect-to-ollie";
  const primaryReady = isOllieTab ? connected : traceReceived;

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
    trackEvent(OpikEvent.ONBOARDING_SKIPPED, { agent_name: agentName });
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
              {InviteDevButton && <InviteDevButton size="2xs" />}
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
      title={`Set up Opik for ${agentName}`}
      description="Connect your repo so Opik can help set up tracing, or instrument your code manually."
      showFooterSeparator
      footer={
        primaryReady ? (
          <Button
            onClick={handleViewTraces}
            id="onboarding-step2-view-traces"
            data-fs-element="onboarding-step2-view-traces"
          >
            Explore Opik
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
          {showOllieTab && (
            <TabsTrigger value="connect-to-ollie" variant="underline">
              AI-assisted setup
            </TabsTrigger>
          )}
          {!showOllieTab && (
            <TabsTrigger value="install-with-ai" variant="underline">
              Use Opik skills
            </TabsTrigger>
          )}
          <TabsTrigger value="manual-integration" variant="underline">
            Manual setup
          </TabsTrigger>
        </TabsList>

        {showOllieTab && (
          <TabsContent value="connect-to-ollie">
            <ConnectToOllieTab connected={connected} />
          </TabsContent>
        )}

        {!showOllieTab && (
          <TabsContent value="install-with-ai">
            <InstallWithAITab traceReceived={traceReceived} />
          </TabsContent>
        )}

        <TabsContent value="manual-integration">
          <ManualIntegrationList
            onSelectIntegration={setSelectedIntegrationId}
            showInstallWithAI={showOllieTab}
            traceReceived={traceReceived}
            activeCategory={manualCategory}
            onCategoryChange={setManualCategory}
          />
        </TabsContent>
      </Tabs>
    </AgentOnboardingCard>
  );
};

export default ConnectAgentStep;
