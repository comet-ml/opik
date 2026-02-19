import usePluginsStore from "@/store/PluginsStore";
import NoAccessPageGuard from "@/components/layout/NoAccessPageGuard/NoAccessPageGuard";

const DashboardsPageGuard = () => {
  const DashboardsPageGuardPlugin = usePluginsStore(
    (state) => state.DashboardsPageGuard,
  );

  if (DashboardsPageGuardPlugin) {
    return <DashboardsPageGuardPlugin />;
  }

  return <NoAccessPageGuard resourceName="dashboards" canViewPage />;
};

export default DashboardsPageGuard;
