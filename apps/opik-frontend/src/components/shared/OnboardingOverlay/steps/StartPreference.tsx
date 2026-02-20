import React from "react";
import { Link } from "@tanstack/react-router";
import { useFeatureFlagVariantKey } from "posthog-js/react";
import useAppStore from "@/store/AppStore";
import OnboardingStep from "@/components/shared/OnboardingOverlay/OnboardingStep";
import { usePermissions } from "@/contexts/PermissionsContext";

const OPTIONS = {
  TRACE_APP: "Trace an app – Debug and analyze every AI interaction",
  TEST_PROMPTS:
    "Test prompts – Test prompts across models and providers approaches",
  RUN_EVALUATIONS:
    "Run evaluations – Run experiments and track performance across versions of your app",
} as const;

const FEATURE_FLAG_KEY = "onboarding-start-exploring-test";

const StartPreference: React.FC = () => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const variant = useFeatureFlagVariantKey(FEATURE_FLAG_KEY);

  const { canViewExperiments } = usePermissions();

  // A/B test: control shows "Skip", test shows "Start exploring Opik"
  // Enhanced flow also opens create experiment dialog when clicking "Run evaluations"
  const showEnhancedOnboarding = variant === "test";

  return (
    <OnboardingStep className="max-w-full">
      <OnboardingStep.BackButton />
      <OnboardingStep.Title>How would you like to start?</OnboardingStep.Title>

      <OnboardingStep.AnswerList className="w-full gap-4 space-y-0 lg:flex-row">
        {showEnhancedOnboarding ? (
          <Link
            to="/$workspaceName/home"
            params={{ workspaceName }}
            search={{ quickstart: 1 }}
            className="w-full"
          >
            <OnboardingStep.AnswerCard option={OPTIONS.TRACE_APP} />
          </Link>
        ) : (
          <OnboardingStep.AnswerCard option={OPTIONS.TRACE_APP} />
        )}

        <Link
          to="/$workspaceName/playground"
          params={{ workspaceName }}
          className="w-full"
        >
          <OnboardingStep.AnswerCard option={OPTIONS.TEST_PROMPTS} />
        </Link>

        {canViewExperiments && (
          <Link
            to="/$workspaceName/experiments"
            params={{ workspaceName }}
            search={
              showEnhancedOnboarding
                ? {
                    new: {
                      experiment: true,
                      datasetName: "Opik Demo Questions",
                    },
                  }
                : undefined
            }
            className="w-full"
          >
            <OnboardingStep.AnswerCard option={OPTIONS.RUN_EVALUATIONS} />
          </Link>
        )}
      </OnboardingStep.AnswerList>

      {showEnhancedOnboarding ? (
        <Link to="/$workspaceName/home" params={{ workspaceName }}>
          <OnboardingStep.StartExploring />
        </Link>
      ) : (
        <OnboardingStep.Skip />
      )}
    </OnboardingStep>
  );
};

export default StartPreference;
