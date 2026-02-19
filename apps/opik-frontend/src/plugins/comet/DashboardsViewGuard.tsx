import { useEffect } from "react";
import useUserPermission from "@/plugins/comet/useUserPermission";
import { VIEW_TYPE } from "@/components/pages-shared/dashboards/ViewSelector/ViewSelector";

export interface DashboardsViewGuardProps {
  view: VIEW_TYPE | null;
  setView: (view: VIEW_TYPE) => void;
}

const DashboardsViewGuard: React.FC<DashboardsViewGuardProps> = ({
  view,
  setView,
}) => {
  const { canViewDashboards } = useUserPermission();

  useEffect(() => {
    if (view === VIEW_TYPE.DASHBOARDS && canViewDashboards === false) {
      setView(VIEW_TYPE.DETAILS);
    }
  }, [view, canViewDashboards, setView]);

  return null;
};

export default DashboardsViewGuard;
