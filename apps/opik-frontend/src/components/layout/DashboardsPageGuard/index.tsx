import { usePermissions } from "@/contexts/PermissionsContext";
import NoAccessPageGuard from "@/components/layout/NoAccessPageGuard/NoAccessPageGuard";

const DashboardsPageGuard = () => {
  const {
    permissions: { canViewDashboards },
  } = usePermissions();

  return (
    <NoAccessPageGuard
      resourceName="dashboards"
      canViewPage={canViewDashboards}
    />
  );
};

export default DashboardsPageGuard;
