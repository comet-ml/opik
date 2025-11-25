import React from "react";

import { DashboardWidgetComponentProps } from "@/types/dashboard";
import ProjectMetricsWidget from "./ProjectMetricsWidget";

export const createProjectWidgetResolver = () => {
  return (type: string): React.ComponentType<DashboardWidgetComponentProps> => {
    switch (type) {
      case "chart":
        return ProjectMetricsWidget;
      default:
        return ProjectMetricsWidget;
    }
  };
};
