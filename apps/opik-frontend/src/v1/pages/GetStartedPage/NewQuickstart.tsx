import React, { useEffect } from "react";
import { IntegrationExplorer } from "@/v1/pages-shared/onboarding/IntegrationExplorer";
import OnboardingOverlay from "@/v1/pages-shared/OnboardingOverlay/OnboardingOverlay";
import OnboardingIntegrationsPage from "@/shared/OnboardingIntegrationsPage/OnboardingIntegrationsPage";
import {
  ONBOARDING_STEP_FINISHED,
  ONBOARDING_STEP_KEY,
} from "@/v1/pages-shared/OnboardingOverlay/OnboardingOverlayContext";
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
    <OnboardingIntegrationsPage
      IntegrationExplorer={IntegrationExplorer}
      source="get-started"
    />
  );
};

export default NewQuickstart;
