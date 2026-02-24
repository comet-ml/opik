import React from "react";
import DashboardWidget from "./DashboardWidget";
import { DashboardWidget as DashboardWidgetType } from "@/types/dashboard";

interface DashboardWidgetDisabledProps {
  widget: DashboardWidgetType;
  disabledMessage?: string;
}

const DashboardWidgetDisabled: React.FunctionComponent<
  DashboardWidgetDisabledProps
> = ({ widget, disabledMessage }) => {
  return (
    <DashboardWidget className="opacity-60">
      <DashboardWidget.Header
        title={widget.title || widget.generatedTitle || ""}
        subtitle={widget.subtitle}
        dragHandle={<DashboardWidget.DragHandle />}
      />
      <DashboardWidget.Content>
        <DashboardWidget.DisabledState
          title="Widget not available"
          message={disabledMessage}
        />
      </DashboardWidget.Content>
    </DashboardWidget>
  );
};

export default DashboardWidgetDisabled;
