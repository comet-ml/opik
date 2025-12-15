import React, { useEffect } from "react";
import { IntegrationExplorer } from "@/components/pages-shared/onboarding/IntegrationExplorer";
import OnboardingOverlay from "@/components/shared/OnboardingOverlay/OnboardingOverlay";
import {
  ONBOARDING_STEP_FINISHED,
  ONBOARDING_STEP_KEY,
} from "@/components/shared/OnboardingOverlay/OnboardingOverlayContext";
import useLocalStorageState from "use-local-storage-state";
import posthog from "posthog-js";

export interface NewQuickstartProps {
  shouldSkipQuestions?: boolean;
}

const NewQuickstart: React.FunctionComponent<NewQuickstartProps> = ({
  shouldSkipQuestions = false,
}) => {
  const [currentOnboardingStep] = useLocalStorageState(ONBOARDING_STEP_KEY);

  const showIntegrationList =
    currentOnboardingStep === ONBOARDING_STEP_FINISHED || shouldSkipQuestions;

  // Update URL hash when showing integration list
  // This allows FullStory and PostHog to distinguish this step by URL
  useEffect(() => {
    if (!showIntegrationList) return;

    const hash = "#integration_list";

    if (window.location.hash !== hash) {
      window.history.replaceState(null, "", hash);

      // Manually trigger PostHog pageview for hash changes
      try {
        if (posthog.is_capturing()) {
          posthog.capture("$pageview");
        }
      } catch (error) {
        // PostHog may not be initialized or available
      }
    }
  }, [showIntegrationList]);

  if (!showIntegrationList) {
    return <OnboardingOverlay />;
  }

  return (
    <div className="w-full pb-10">
      <div className="mx-auto max-w-[1040px]">
        <div className="mb-3 mt-6 flex items-center justify-between md:mt-10">
          <h1 className="md:comet-title-xl comet-title-l">
            Get started with Opik
          </h1>
          {/* <LoggedDataStatus status="waiting" /> */}
        </div>
        <div className="comet-body-s mb-10 text-muted-slate">
          Opik helps you improve your LLM features by tracking what happens
          behind the scenes. Integrate Opik to unlock evaluations, experiments,
          and debugging.
        </div>

        <IntegrationExplorer source="get-started">
          <div className="mb-8 flex flex-col justify-between gap-6 md:flex-row md:items-center">
            <IntegrationExplorer.Search />

            <div className="flex flex-wrap items-center gap-6 md:gap-3">
              <IntegrationExplorer.CopyApiKey />
              <IntegrationExplorer.GetHelp />
              <IntegrationExplorer.Skip />
            </div>
          </div>

          <div className="mb-4 grid grid-cols-1 gap-4 lg:grid-cols-2">
            <IntegrationExplorer.QuickInstall />
            <IntegrationExplorer.TypeScriptSDK />
          </div>

          <IntegrationExplorer.Tabs>
            <IntegrationExplorer.Grid />
          </IntegrationExplorer.Tabs>
        </IntegrationExplorer>
      </div>
    </div>
  );
};

export default NewQuickstart;
