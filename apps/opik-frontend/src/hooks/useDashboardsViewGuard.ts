import { useEffect } from "react";
import { usePermissions } from "@/contexts/PermissionsContext";
import { VIEW_TYPE } from "@/components/pages-shared/dashboards/ViewSelector/ViewSelector";

interface UseDashboardsViewGuardParams {
  view: VIEW_TYPE | null;
  setView: (view: VIEW_TYPE) => void;
}

const useDashboardsViewGuard = ({
  view,
  setView,
}: UseDashboardsViewGuardParams) => {
  const {
    permissions: { canViewDashboards },
  } = usePermissions();

  useEffect(() => {
    if (view === VIEW_TYPE.DASHBOARDS && canViewDashboards === false) {
      setView(VIEW_TYPE.DETAILS);
    }
  }, [view, canViewDashboards, setView]);
};

export default useDashboardsViewGuard;
