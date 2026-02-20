import React from "react";
import usePluginsStore from "@/store/PluginsStore";
import ViewSelectorComponent, { ViewSelectorProps } from "./ViewSelector";

const ViewSelector: React.FC<ViewSelectorProps> = (props) => {
  const ViewSelectorPlugin = usePluginsStore((state) => state.ViewSelector);

  if (ViewSelectorPlugin) {
    return <ViewSelectorPlugin {...props} />;
  }

  return <ViewSelectorComponent {...props} canViewDashboards />;
};

export default ViewSelector;
