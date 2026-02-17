import React from "react";
import useUserPermission from "./useUserPermission";
import ViewSelectorComponent, {
  ViewSelectorProps,
} from "@/components/pages-shared/dashboards/ViewSelector/ViewSelector";

const ViewSelector: React.FC<ViewSelectorProps> = (props) => {
  const { canViewDashboards } = useUserPermission();

  return (
    <ViewSelectorComponent {...props} canViewDashboards={canViewDashboards} />
  );
};

export default ViewSelector;
