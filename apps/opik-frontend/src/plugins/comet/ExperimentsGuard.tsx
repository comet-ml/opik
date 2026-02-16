import useUserPermission from "@/plugins/comet/useUserPermission";
import ExperimentsGuardCore from "@/components/layout/ExperimentsGuard/ExperimentsGuard";

const ExperimentsGuard = () => {
  const { canViewExperiments } = useUserPermission();

  return <ExperimentsGuardCore canViewExperiments={canViewExperiments} />;
};

export default ExperimentsGuard;
