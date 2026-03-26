import React, { useState } from "react";
import { ChevronLeft, ChevronsRight, MonitorPlay, Undo2 } from "lucide-react";
import { Button } from "@/ui/button";
import { Tabs, TabsList, TabsTrigger, TabsContent } from "@/ui/tabs";
import Slack from "@/icons/slack.svg?react";
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

const ConnectAgentStep: React.FC = () => {
  const { goToStep, agentName } = useAgentOnboarding();
  const [activeTab, setActiveTab] = useState("install-with-ai");
  const [selectedIntegrationId, setSelectedIntegrationId] = useState<
    string | null
  >(null);

  const selectedIntegration = selectedIntegrationId
    ? INTEGRATIONS.find((i) => i.id === selectedIntegrationId)
    : undefined;

  const handleTabChange = (value: string) => {
    setActiveTab(value);
    setSelectedIntegrationId(null);
  };

  const handleBack = () => {
    goToStep(AGENT_ONBOARDING_STEPS.AGENT_NAME, { agentName });
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
                className="flex-1"
                asChild
                id="onboarding-slack"
                data-fs-element="OnboardingSlack"
              >
                <a href={SLACK_LINK} target="_blank" rel="noopener noreferrer">
                  <Slack className="mr-2 size-4" />
                  Get help in Slack
                </a>
              </Button>
              <Button
                variant="outline"
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
                  <MonitorPlay className="mr-2 size-4" />
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
        <>
          <Button
            variant="link"
            onClick={handleBack}
            className="comet-body-s mr-auto text-muted-slate"
            id="onboarding-step2-back"
            data-fs-element="onboarding-step2-back"
          >
            <ChevronLeft className="size-3.5" />
            Back
          </Button>
          <Button
            variant="link"
            onClick={handleSkip}
            className="comet-body-s text-muted-slate"
            id="onboarding-step2-skip"
            data-fs-element="onboarding-step2-skip"
          >
            Skip for now
            <ChevronsRight className="size-3.5" />
          </Button>
        </>
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
          <InstallWithAITab />
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
