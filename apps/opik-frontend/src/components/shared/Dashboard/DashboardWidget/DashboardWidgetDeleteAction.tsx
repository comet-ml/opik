import React, { useCallback, useState } from "react";
import { Trash2 } from "lucide-react";

import { Button } from "@/components/ui/button";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import ConfirmDialog from "@/components/shared/ConfirmDialog/ConfirmDialog";
import { useDashboardStore, selectDeleteWidget } from "@/store/DashboardStore";

type DashboardWidgetDeleteActionProps = {
  sectionId: string;
  widgetId: string;
  widgetTitle: string;
};

const DashboardWidgetDeleteAction: React.FC<
  DashboardWidgetDeleteActionProps
> = ({ sectionId, widgetId, widgetTitle }) => {
  const [showDeleteDialog, setShowDeleteDialog] = useState(false);
  const deleteWidget = useDashboardStore(selectDeleteWidget);

  const handleDeleteClick = useCallback((e: React.MouseEvent) => {
    e.stopPropagation();
    setShowDeleteDialog(true);
  }, []);

  const handleConfirmDelete = useCallback(() => {
    deleteWidget(sectionId, widgetId);
  }, [sectionId, widgetId, deleteWidget]);

  return (
    <>
      <TooltipWrapper content="Delete widget">
        <Button
          variant="minimal"
          size="icon-3xs"
          onClick={handleDeleteClick}
          className="text-light-slate hover:text-foreground"
        >
          <Trash2 className="size-4" />
        </Button>
      </TooltipWrapper>
      <ConfirmDialog
        open={showDeleteDialog}
        setOpen={setShowDeleteDialog}
        onConfirm={handleConfirmDelete}
        title="Delete widget?"
        description={`The '${widgetTitle}' widget will be permanently deleted and cannot be recovered.`}
        confirmText="Yes, delete"
        confirmButtonVariant="destructive"
      />
    </>
  );
};

export default DashboardWidgetDeleteAction;
