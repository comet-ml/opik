import React from "react";
import { Link } from "@tanstack/react-router";
import OnboardingStep from "../OnboardingStep";
import useAppStore from "@/store/AppStore";

const OPTIONS = {
  TRACE_APP: "Trace an app – Debug and analyze every AI interaction",
  TEST_PROMPTS:
    "Test prompts – Test prompts across models and providers approaches",
  RUN_EVALUATIONS:
    "Run evaluations – Run experiments and and track performance across versions of your app",
} as const;

const StartPreference: React.FC = () => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);

  return (
    <OnboardingStep className="max-w-full">
      <OnboardingStep.BackButton />
      <OnboardingStep.Title>How would you like to start?</OnboardingStep.Title>

      <OnboardingStep.AnswerList className="w-full gap-4 space-y-0 lg:flex-row">
        <OnboardingStep.AnswerCard option={OPTIONS.TRACE_APP} />

        <Link
          to="/$workspaceName/prompts"
          params={{ workspaceName }}
          className="w-full"
        >
          <OnboardingStep.AnswerCard option={OPTIONS.TEST_PROMPTS} />
        </Link>

        <Link
          to="/$workspaceName/experiments"
          params={{ workspaceName }}
          className="w-full"
        >
          <OnboardingStep.AnswerCard option={OPTIONS.RUN_EVALUATIONS} />
        </Link>
      </OnboardingStep.AnswerList>

      <OnboardingStep.Skip />
    </OnboardingStep>
  );
};

export default StartPreference;
