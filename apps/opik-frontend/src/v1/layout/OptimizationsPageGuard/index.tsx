import { usePermissions } from "@/contexts/PermissionsContext";
import NoAccessPageGuard from "@/v1/layout/NoAccessPageGuard/NoAccessPageGuard";

const OptimizationsPageGuard = () => {
  const {
    permissions: { canViewOptimizationRuns },
  } = usePermissions();

  return (
    <NoAccessPageGuard
      resourceName="optimization runs"
      canViewPage={canViewOptimizationRuns}
    />
  );
};

export default OptimizationsPageGuard;
