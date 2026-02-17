import usePluginsStore from "@/store/PluginsStore";
import PageGuard from "@/components/layout/PageGuard/PageGuard";

const DashboardsGuard = () => {
  const DashboardsGuardPlugin = usePluginsStore(
    (state) => state.DashboardsGuard,
  );

  if (DashboardsGuardPlugin) {
    return <DashboardsGuardPlugin />;
  }

  return <PageGuard canViewPage />;
};

export default DashboardsGuard;
