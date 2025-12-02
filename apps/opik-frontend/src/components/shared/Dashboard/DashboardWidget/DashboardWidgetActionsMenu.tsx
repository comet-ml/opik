import React, { useState, useCallback } from "react";
import {
  MoreHorizontal,
  Pencil,
  Copy,
  ArrowUpDown,
  Trash2,
} from "lucide-react";
import { useShallow } from "zustand/react/shallow";

import { Button } from "@/components/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
  DropdownMenuSub,
  DropdownMenuSubContent,
  DropdownMenuSubTrigger,
  DropdownMenuItem,
} from "@/components/ui/dropdown-menu";
import {
  useDashboardStore,
  selectSections,
  selectMoveWidget,
  selectDuplicateWidget,
  selectDeleteWidget,
  selectOnAddEditWidgetCallback,
} from "@/store/DashboardStore";
import ConfirmDialog from "@/components/shared/ConfirmDialog/ConfirmDialog";

type DashboardWidgetActionsMenuProps = {
  sectionId: string;
  widgetId: string;
  widgetTitle: string;
  customMenuItems?: React.ReactNode;
  hideEdit?: boolean;
  hideDuplicate?: boolean;
  hideMove?: boolean;
  hideDelete?: boolean;
  onOpenChange?: (open: boolean) => void;
};

const DashboardWidgetActionsMenu: React.FunctionComponent<
  DashboardWidgetActionsMenuProps
> = ({
  sectionId,
  widgetId,
  widgetTitle,
  customMenuItems,
  hideEdit = false,
  hideDuplicate = false,
  hideMove = false,
  hideDelete = false,
  onOpenChange,
}) => {
  const [open, setOpen] = useState(false);
  const [deleteDialogOpen, setDeleteDialogOpen] = useState(false);

  const sections = useDashboardStore(useShallow(selectSections));
  const moveWidget = useDashboardStore(selectMoveWidget);
  const duplicateWidget = useDashboardStore(selectDuplicateWidget);
  const deleteWidget = useDashboardStore(selectDeleteWidget);
  const onAddEditWidgetCallback = useDashboardStore(
    selectOnAddEditWidgetCallback,
  );

  const handleOpenChange = (newOpen: boolean) => {
    setOpen(newOpen);
    onOpenChange?.(newOpen);
  };

  const handleDeleteDialogOpenChange = useCallback(
    (isOpen: boolean) => {
      setDeleteDialogOpen(isOpen);
      onOpenChange?.(open || isOpen);
    },
    [open, onOpenChange],
  );

  const handleEdit = useCallback(
    (e: React.MouseEvent) => {
      e.stopPropagation();
      onAddEditWidgetCallback?.({ sectionId, widgetId });
    },
    [onAddEditWidgetCallback, sectionId, widgetId],
  );

  const handleDuplicate = useCallback(
    (e: React.MouseEvent) => {
      e.stopPropagation();
      duplicateWidget(sectionId, widgetId);
    },
    [sectionId, widgetId, duplicateWidget],
  );

  const handleDeleteClick = useCallback((e: React.MouseEvent) => {
    e.stopPropagation();
    setDeleteDialogOpen(true);
  }, []);

  const handleConfirmDelete = useCallback(() => {
    deleteWidget(sectionId, widgetId);
  }, [sectionId, widgetId, deleteWidget]);

  const availableSections = sections.filter((s) => s.id !== sectionId);
  const showMove = !hideMove && availableSections.length > 0;

  const handleMoveToSection = (targetSectionId: string) => {
    moveWidget(sectionId, targetSectionId, widgetId);
  };

  const hasStandardActions = !hideEdit || !hideDuplicate || showMove;
  const showSeparator = hasStandardActions && !hideDelete;

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
        <DropdownMenuContent align="end" className="w-52">
          {!hideEdit && (
            <DropdownMenuItem onClick={handleEdit}>
              <Pencil className="mr-2 size-4" />
              Edit widget
            </DropdownMenuItem>
          )}
          {!hideDuplicate && (
            <DropdownMenuItem onClick={handleDuplicate}>
              <Copy className="mr-2 size-4" />
              Duplicate widget
            </DropdownMenuItem>
          )}
          {showMove && (
            <DropdownMenuSub>
              <DropdownMenuSubTrigger>
                <ArrowUpDown className="mr-2 size-4" />
                Move to section
              </DropdownMenuSubTrigger>
              <DropdownMenuSubContent>
                {availableSections.map((section) => (
                  <DropdownMenuItem
                    key={section.id}
                    onClick={() => handleMoveToSection(section.id)}
                  >
                    {section.title}
                  </DropdownMenuItem>
                ))}
              </DropdownMenuSubContent>
            </DropdownMenuSub>
          )}
          {customMenuItems && (
            <>
              {hasStandardActions && <DropdownMenuSeparator />}
              {customMenuItems}
            </>
          )}
          {showSeparator && <DropdownMenuSeparator />}
          {!hideDelete && (
            <DropdownMenuItem
              onClick={handleDeleteClick}
              className="text-destructive focus:text-destructive"
            >
              <Trash2 className="mr-2 size-4" />
              Delete widget
            </DropdownMenuItem>
          )}
        </DropdownMenuContent>
      </DropdownMenu>

      <ConfirmDialog
        open={deleteDialogOpen}
        setOpen={handleDeleteDialogOpenChange}
        onConfirm={handleConfirmDelete}
        title="Delete widget?"
        description={`This widget will be removed from this dashboard. You can still undo this change before saving the dashboard.`}
        confirmText={`Delete ${widgetTitle}`}
        confirmButtonVariant="destructive"
      />
    </>
  );
};

export default DashboardWidgetActionsMenu;
