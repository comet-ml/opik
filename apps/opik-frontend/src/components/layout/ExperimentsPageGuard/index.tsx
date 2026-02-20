import { usePermissions } from "@/contexts/PermissionsContext";
import NoAccessPageGuard from "@/components/layout/NoAccessPageGuard/NoAccessPageGuard";

const ExperimentsPageGuard = () => {
  const { canViewExperiments } = usePermissions();

  return (
    <NoAccessPageGuard
      resourceName="experiments"
      canViewPage={canViewExperiments}
    />
  );
};

export default ExperimentsPageGuard;
