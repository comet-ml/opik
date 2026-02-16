import React from "react";
import ExperimentsLink, {
  ExperimentsLinkProps,
} from "@/components/shared/OnboardingOverlay/steps/StartPreference/ExperimentsLink";
import useUserPermission from "./useUserPermission";

const StartPreferenceExperimentsLink: React.FC<ExperimentsLinkProps> = (
  props,
) => {
  const { canViewExperiments } = useUserPermission();

  return <ExperimentsLink {...props} canViewExperiments={canViewExperiments} />;
};

export default StartPreferenceExperimentsLink;
