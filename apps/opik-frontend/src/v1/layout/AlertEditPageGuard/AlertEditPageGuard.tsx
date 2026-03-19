import { usePermissions } from "@/contexts/PermissionsContext";
import NoAccessPageGuard from "@/v1/layout/NoAccessPageGuard/NoAccessPageGuard";
import AddEditAlertPage from "@/v1/pages/AlertsPage/AddEditAlertPage/AddEditAlertPage";

const AlertEditPageGuard = () => {
  const {
    permissions: { canUpdateAlerts },
  } = usePermissions();

  return (
    <NoAccessPageGuard
      canViewPage={canUpdateAlerts}
      message="You don't have permissions to manage alerts in this workspace."
    >
      <AddEditAlertPage />
    </NoAccessPageGuard>
  );
};

export default AlertEditPageGuard;
