import { usePermissions } from "@/contexts/PermissionsContext";
import NoAccessPageGuard from "@/v2/layout/NoAccessPageGuard/NoAccessPageGuard";

const ExperimentsPageGuard = () => {
  const {
    permissions: { canViewExperiments },
  } = usePermissions();

  return (
    <NoAccessPageGuard
      resourceName="experiments"
      canViewPage={canViewExperiments}
    />
  );
};

export default ExperimentsPageGuard;
