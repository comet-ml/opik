import React from "react";
import { Link } from "@tanstack/react-router";
import useAppStore from "@/store/AppStore";
import OnboardingStep from "@/components/shared/OnboardingOverlay/OnboardingStep";

export interface ExperimentsLinkProps {
  optionDescription: string;
  showEnhancedOnboarding: boolean;
}

const ExperimentsLink: React.FC<
  ExperimentsLinkProps & { canViewExperiments: boolean }
> = ({ optionDescription, showEnhancedOnboarding, canViewExperiments }) => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);

  if (!canViewExperiments) return null;

  return (
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
      <OnboardingStep.AnswerCard option={optionDescription} />
    </Link>
  );
};

export default ExperimentsLink;
