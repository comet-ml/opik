import React, { useCallback, useState } from "react";
import { Copy, MoreHorizontal, Pencil, RefreshCw, Trash } from "lucide-react";

import { Button } from "@/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/ui/dropdown-menu";
import {
  useDashboardStore,
  selectDeleteWidget,
  selectDuplicateWidget,
  selectOnAddEditWidgetCallback,
} from "@/store/DashboardStore";
import ConfirmDialog from "@/shared/ConfirmDialog/ConfirmDialog";

type OllieChartActionsMenuProps = {
  sectionId: string;
  widgetId: string;
  widgetTitle: string;
  // Edit in Ollie; without it (Ollie off) the item opens the widget editor instead.
  onEditInOllie?: () => void;
  onRefresh: () => void;
  onOpenChange?: (open: boolean) => void;
};

const OllieChartActionsMenu: React.FunctionComponent<
  OllieChartActionsMenuProps
> = ({
  sectionId,
  widgetId,
  widgetTitle,
  onEditInOllie,
  onRefresh,
  onOpenChange,
}) => {
  const [open, setOpen] = useState(false);
  const [deleteDialogOpen, setDeleteDialogOpen] = useState(false);
  const duplicateWidget = useDashboardStore(selectDuplicateWidget);
  const deleteWidget = useDashboardStore(selectDeleteWidget);
  const onAddEditWidgetCallback = useDashboardStore(
    selectOnAddEditWidgetCallback,
  );

  const handleOpenChange = (next: boolean) => {
    setOpen(next);
    onOpenChange?.(next);
  };

  const handleDeleteDialogOpenChange = useCallback(
    (isOpen: boolean) => {
      setDeleteDialogOpen(isOpen);
      onOpenChange?.(open || isOpen);
    },
    [open, onOpenChange],
  );

  const run = (action: () => void) => (e: React.MouseEvent) => {
    e.stopPropagation();
    action();
  };

  return (
    <>
      <DropdownMenu open={open} onOpenChange={handleOpenChange}>
        <DropdownMenuTrigger asChild>
          <Button
            variant="minimal"
            size="icon-3xs"
            onClick={(e) => e.stopPropagation()}
            className="text-light-slate hover:text-foreground"
          >
            <MoreHorizontal className="size-4" />
          </Button>
        </DropdownMenuTrigger>
        <DropdownMenuContent align="end" className="w-44">
          <DropdownMenuItem
            onClick={run(() =>
              onEditInOllie
                ? onEditInOllie()
                : onAddEditWidgetCallback?.({ sectionId, widgetId }),
            )}
          >
            <Pencil className="mr-2 size-4" />
            {onEditInOllie ? "Edit in Ollie" : "Edit"}
          </DropdownMenuItem>
          <DropdownMenuItem onClick={run(onRefresh)}>
            <RefreshCw className="mr-2 size-4" />
            Refresh
          </DropdownMenuItem>
          <DropdownMenuItem
            onClick={run(() => duplicateWidget(sectionId, widgetId))}
          >
            <Copy className="mr-2 size-4" />
            Duplicate
          </DropdownMenuItem>
          <DropdownMenuSeparator />
          <DropdownMenuItem
            variant="destructive"
            onClick={run(() => setDeleteDialogOpen(true))}
          >
            <Trash className="mr-2 size-4" />
            Remove
          </DropdownMenuItem>
        </DropdownMenuContent>
      </DropdownMenu>

      <ConfirmDialog
        open={deleteDialogOpen}
        setOpen={handleDeleteDialogOpenChange}
        onConfirm={() => deleteWidget(sectionId, widgetId)}
        title="Remove widget?"
        description="Are you sure you want to remove this widget? This action cannot be undone."
        confirmText={`Remove ${widgetTitle}`}
        confirmButtonVariant="destructive"
      />
    </>
  );
};

export default OllieChartActionsMenu;
