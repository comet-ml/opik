import { usePermissions } from "@/contexts/PermissionsContext";
import NoAccessPageGuard from "@/v2/layout/NoAccessPageGuard/NoAccessPageGuard";

const OptimizationStudioPageGuard = () => {
  const {
    permissions: { canUseOptimizationStudio },
  } = usePermissions();

  return (
    <NoAccessPageGuard
      canViewPage={canUseOptimizationStudio}
      message="You don't have permissions to start new optimization runs in this workspace."
    />
  );
};

export default OptimizationStudioPageGuard;
