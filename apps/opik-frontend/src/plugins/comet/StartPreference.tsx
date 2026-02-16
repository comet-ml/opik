import useUserPermission from "@/plugins/comet/useUserPermission";
import StartPreferenceContent from "@/components/shared/OnboardingOverlay/steps/StartPreference";

const StartPreference = () => {
  const { canViewExperiments } = useUserPermission();

  return <StartPreferenceContent canViewExperiments={canViewExperiments} />;
};

export default StartPreference;
