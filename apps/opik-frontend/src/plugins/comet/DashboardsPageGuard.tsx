import useUserPermission from "@/plugins/comet/useUserPermission";
import NoAccessPageGuard from "@/components/layout/NoAccessPageGuard/NoAccessPageGuard";

const DashboardsGuard = () => {
  const { canViewDashboards } = useUserPermission();

  return (
    <NoAccessPageGuard
      resourceName="dashboards"
      canViewPage={canViewDashboards}
    />
  );
};

export default DashboardsGuard;
