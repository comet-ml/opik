import React, { useState, useCallback, useEffect } from "react";

import {
  useDashboardStore,
  selectAddWidget,
  selectUpdateWidget,
} from "@/store/DashboardStore";
import DashboardSectionsContainer from "@/components/shared/Dashboard/Dashboard";
import AddSectionButton from "@/components/shared/Dashboard/DashboardSection/AddSectionButton";
import WidgetConfigDialog from "@/components/shared/Dashboard/WidgetConfigDialog/WidgetConfigDialog";
import { DashboardWidget } from "@/types/dashboard";

const DashboardContent: React.FunctionComponent = () => {
  const [widgetDialogOpen, setWidgetDialogOpen] = useState(false);
  const [targetSectionId, setTargetSectionId] = useState<string | null>(null);
  const [targetWidgetId, setTargetWidgetId] = useState<string | null>(null);

  const addSection = useDashboardStore((state) => state.addSection);
  const addWidget = useDashboardStore(selectAddWidget);
  const updateWidget = useDashboardStore(selectUpdateWidget);

  useEffect(() => {
    useDashboardStore
      .getState()
      .setOnAddEditWidgetCallback(({ sectionId, widgetId }) => {
        setTargetSectionId(sectionId);
        setTargetWidgetId(widgetId || null);
        setWidgetDialogOpen(true);
      });

    return () => {
      useDashboardStore.getState().setOnAddEditWidgetCallback(null);
    };
  }, []);

  const handleSaveWidget = useCallback(
    (widgetData: DashboardWidget) => {
      if (!targetSectionId) return;

      if (targetWidgetId) {
        updateWidget(targetSectionId, targetWidgetId, widgetData);
      } else {
        addWidget(targetSectionId, widgetData);
      }
    },
    [targetSectionId, targetWidgetId, addWidget, updateWidget],
  );

  return (
    <>
      <DashboardSectionsContainer />

      <div className="text-clip rounded-md">
        <AddSectionButton onAddSection={addSection} />
      </div>

      <WidgetConfigDialog
        open={widgetDialogOpen}
        onOpenChange={setWidgetDialogOpen}
        sectionId={targetSectionId || ""}
        widgetId={targetWidgetId || undefined}
        onSave={handleSaveWidget}
      />
    </>
  );
};

export default DashboardContent;
