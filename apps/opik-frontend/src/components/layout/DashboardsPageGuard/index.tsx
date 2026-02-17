import usePluginsStore from "@/store/PluginsStore";
import PageGuard from "@/components/layout/PageGuard/PageGuard";

const DashboardsPageGuard = () => {
  const DashboardsPageGuardPlugin = usePluginsStore(
    (state) => state.DashboardsPageGuard,
  );

  if (DashboardsPageGuardPlugin) {
    return <DashboardsPageGuardPlugin />;
  }

  return <PageGuard canViewPage />;
};

export default DashboardsPageGuard;
