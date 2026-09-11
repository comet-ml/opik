import { usePermissions } from "@/contexts/PermissionsContext";
import NoAccessPageGuard from "@/v2/layout/NoAccessPageGuard/NoAccessPageGuard";
import AlertsRouteWrapper from "@/v2/pages/AlertsPage/AlertsRouteWrapper";

const AlertsPageGuard = () => {
  const {
    permissions: { canViewAlerts },
  } = usePermissions();

  return (
    <NoAccessPageGuard
      canViewPage={canViewAlerts}
      message="You don't have permissions to view alerts in this workspace."
    >
      <AlertsRouteWrapper />
    </NoAccessPageGuard>
  );
};

export default AlertsPageGuard;
