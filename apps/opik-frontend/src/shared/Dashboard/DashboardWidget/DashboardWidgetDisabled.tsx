import React from "react";
import DashboardWidget from "./DashboardWidget";
import { DashboardWidget as DashboardWidgetType } from "@/types/dashboard";
import { useDashboardStore, selectReadOnly } from "@/store/DashboardStore";

interface DashboardWidgetDisabledProps {
  widget: DashboardWidgetType;
  disabledMessage?: string;
}

const DashboardWidgetDisabled: React.FunctionComponent<
  DashboardWidgetDisabledProps
> = ({ widget, disabledMessage }) => {
  const readOnly = useDashboardStore(selectReadOnly);

  return (
    <DashboardWidget className="opacity-60">
      <DashboardWidget.Header
        title={widget.title || widget.generatedTitle || ""}
        subtitle={widget.subtitle}
        readOnly={readOnly}
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
