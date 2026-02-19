import useUserPermission from "@/plugins/comet/useUserPermission";
import NoAccessPageGuard from "@/components/layout/NoAccessPageGuard/NoAccessPageGuard";

const ExperimentsPageGuard = () => {
  const { canViewExperiments } = useUserPermission();

  return (
    <NoAccessPageGuard
      canViewPage={canViewExperiments}
      resourceName="experiments"
    />
  );
};

export default ExperimentsPageGuard;
