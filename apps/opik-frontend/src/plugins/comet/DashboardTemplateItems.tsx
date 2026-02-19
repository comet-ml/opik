import React from "react";
import useUserPermission from "./useUserPermission";
import DashboardTemplateItemsComponent, {
  DashboardTemplateItemsProps,
} from "@/components/pages-shared/dashboards/AddEditCloneDashboardDialog/DashboardTemplateItems";

const DashboardTemplateItems: React.FC<DashboardTemplateItemsProps> = (
  props,
) => {
  const { canViewExperiments } = useUserPermission();

  return (
    <DashboardTemplateItemsComponent
      {...props}
      canViewExperiments={canViewExperiments}
    />
  );
};

export default DashboardTemplateItems;
