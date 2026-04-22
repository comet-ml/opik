import React, { useEffect, useMemo, useState } from "react";
import useLocalStorageState from "use-local-storage-state";
import { ArrowRight, MonitorPlay, Undo2 } from "lucide-react";
import { useFeatureFlagVariantKey } from "posthog-js/react";
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
  AI_ASSISTED_OPIK_SKILLS_FEATURE_FLAG_KEY,
  DEFAULT_ONBOARDING_FLOW,
  TRACES_OLDEST_FIRST_SORTING,
} from "./AgentOnboardingContext";
import AgentOnboardingCard from "./AgentOnboardingCard";
import ConnectToOllieTab from "./ConnectToOllieTab";
import InstallWithAITab from "./InstallWithAITab";
import ManualIntegrationList from "./ManualIntegrationList";
import ManualIntegrationDetail from "./ManualIntegrationDetail";
import ShowDemoProjectButton from "./ShowDemoProjectButton";
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
  // Variants: "control" = AI-assisted tab shows "Install with AI" (Opik skills prompt); "connect-to-ollie" = AI-assisted tab shows "Connect to Ollie"; "manual" = bypasses this modal entirely and renders the full integrations page (handled in NewQuickstart). Undefined falls back to "control" to preserve the Opik skills tab as default.
  const aiAssistedOpikSkillsVariant =
    useFeatureFlagVariantKey(AI_ASSISTED_OPIK_SKILLS_FEATURE_FLAG_KEY) ??
    DEFAULT_ONBOARDING_FLOW;

  const aiAssistedUsesOpikSkills = aiAssistedOpikSkillsVariant === "control";
  const showOllieTab = !!apiKey && !aiAssistedUsesOpikSkills;

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
    setActiveTab((current) => {
      if (showOllieTab && current === "install-with-ai") {
        return "connect-to-ollie";
      }
      if (!showOllieTab && current === "connect-to-ollie") {
        return "install-with-ai";
      }
      return current;
    });
  }, [showOllieTab]);

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

  const tabs = useMemo(
    () => [
      showOllieTab
        ? {
            value: "connect-to-ollie",
            label: "AI-assisted setup",
            content: <ConnectToOllieTab connected={connected} />,
          }
        : {
            value: "install-with-ai",
            label: "AI-assisted setup",
            content: <InstallWithAITab traceReceived={traceReceived} />,
          },
      {
        value: "manual-integration",
        label: "Manual setup",
        content: (
          <ManualIntegrationList
            onSelectIntegration={setSelectedIntegrationId}
            showInstallWithAI={showOllieTab}
            traceReceived={traceReceived}
            activeCategory={manualCategory}
            onCategoryChange={setManualCategory}
          />
        ),
      },
    ],
    [showOllieTab, connected, traceReceived, manualCategory],
  );

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
          <ShowDemoProjectButton />
        )
      }
    >
      <Tabs value={activeTab} onValueChange={handleTabChange}>
        <TabsList variant="underline">
          {tabs.map((tab) => (
            <TabsTrigger key={tab.value} value={tab.value} variant="underline">
              {tab.label}
            </TabsTrigger>
          ))}
        </TabsList>

        {tabs.map((tab) => (
          <TabsContent key={tab.value} value={tab.value}>
            {tab.content}
          </TabsContent>
        ))}
      </Tabs>
    </AgentOnboardingCard>
  );
};

export default ConnectAgentStep;
