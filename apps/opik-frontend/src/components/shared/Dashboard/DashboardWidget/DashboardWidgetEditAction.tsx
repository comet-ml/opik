import React, { useCallback, useState } from "react";
import { Pencil } from "lucide-react";

import { Button } from "@/components/ui/button";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import { WidgetConfigDialog } from "@/components/shared/Dashboard/WidgetConfigDialog";
import { useDashboardStore } from "@/store/DashboardStore";
import { DashboardWidget, UpdateWidgetConfig } from "@/types/dashboard";

interface DashboardWidgetEditActionProps {
  sectionId: string;
  widgetId: string;
}

const DashboardWidgetEditAction: React.FC<DashboardWidgetEditActionProps> = ({
  sectionId,
  widgetId,
}) => {
  const [editDialogOpen, setEditDialogOpen] = useState(false);
  const updateWidget = useDashboardStore((state) => state.updateWidget);

  const handleEdit = useCallback((e: React.MouseEvent) => {
    e.stopPropagation();
    setEditDialogOpen(true);
  }, []);

  const handleSaveWidget = useCallback(
    (widgetData: Partial<DashboardWidget>) => {
      updateWidget(sectionId, widgetId, {
        title: widgetData.title,
        subtitle: widgetData.subtitle,
        config: widgetData.config,
      } as UpdateWidgetConfig);
    },
    [sectionId, widgetId, updateWidget],
  );

  return (
    <>
      <TooltipWrapper content="Edit widget">
        <Button
          variant="minimal"
          size="icon-3xs"
          onClick={handleEdit}
          className="text-light-slate hover:text-foreground"
        >
          <Pencil className="size-4" />
        </Button>
      </TooltipWrapper>

      <WidgetConfigDialog
        open={editDialogOpen}
        onOpenChange={setEditDialogOpen}
        sectionId={sectionId}
        widgetId={widgetId}
        onSave={handleSaveWidget}
      />
    </>
  );
};

export default DashboardWidgetEditAction;
