import React, { useState, useCallback, useEffect } from "react";

import {
  useDashboardStore,
  selectAddWidget,
  selectUpdateWidget,
  selectHasUnsavedChanges,
} from "@/store/DashboardStore";
import DashboardSectionsContainer from "@/components/shared/Dashboard/Dashboard";
import AddSectionButton from "@/components/shared/Dashboard/DashboardSection/AddSectionButton";
import WidgetConfigDialog from "@/components/shared/Dashboard/WidgetConfigDialog/WidgetConfigDialog";
import useNavigationBlocker from "@/hooks/useNavigationBlocker";
import { DashboardWidget } from "@/types/dashboard";

const DashboardContent: React.FunctionComponent = () => {
  const hasUnsavedChanges = useDashboardStore(selectHasUnsavedChanges);
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

  const { DialogComponent } = useNavigationBlocker({
    condition: hasUnsavedChanges,
    title: "You have unsaved changes",
    description:
      "If you leave now, your changes will be lost. Are you sure you want to continue?",
    confirmText: "Leave without saving",
    cancelText: "Stay on page",
  });

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
      {DialogComponent}
    </>
  );
};

export default DashboardContent;
