import useUserPermission from "@/plugins/comet/useUserPermission";
import PageGuard from "@/components/layout/PageGuard/PageGuard";

const ExperimentsPageGuard = () => {
  const { canViewExperiments } = useUserPermission();

  return (
    <PageGuard canViewPage={canViewExperiments} resourceName="experiments" />
  );
};

export default ExperimentsPageGuard;
