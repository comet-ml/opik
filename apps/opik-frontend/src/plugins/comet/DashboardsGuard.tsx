import useUserPermission from "@/plugins/comet/useUserPermission";
import PageGuard from "@/components/layout/PageGuard/PageGuard";

const DashboardsGuard = () => {
  const { canViewDashboards } = useUserPermission();

  return <PageGuard canViewPage={canViewDashboards} />;
};

export default DashboardsGuard;
