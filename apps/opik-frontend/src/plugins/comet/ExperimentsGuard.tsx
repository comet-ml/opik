import useUserPermission from "@/plugins/comet/useUserPermission";
import PageGuard from "@/components/layout/PageGuard/PageGuard";

const ExperimentsGuard = () => {
  const { canViewExperiments } = useUserPermission();

  return <PageGuard canViewPage={canViewExperiments} />;
};

export default ExperimentsGuard;
