import React, { useCallback, useState } from "react";
import {
  GripHorizontal,
  MoreVertical,
  Trash2,
  ArrowUp,
  ArrowDown,
  ChevronDown,
  ChevronRight,
} from "lucide-react";

import { Button } from "@/components/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import ConfirmDialog from "@/components/shared/ConfirmDialog/ConfirmDialog";
import DashboardSectionTitle from "./DashboardSectionTitle";
import { useDashboardStore } from "@/store/DashboardStore";

interface DashboardSectionHeaderProps {
  sectionId: string;
  title: string;
  widgetCount: number;
  expanded: boolean;
  isLastSection: boolean;
  dragHandleProps: Record<string, unknown> | undefined;
  onUpdateTitle: (title: string) => void;
  onDeleteSection: () => void;
  onAddSectionAbove: () => void;
  onAddSectionBelow: () => void;
}

const DashboardSectionHeader: React.FunctionComponent<
  DashboardSectionHeaderProps
> = ({
  sectionId,
  title,
  widgetCount,
  expanded,
  isLastSection,
  dragHandleProps,
  onUpdateTitle,
  onDeleteSection,
  onAddSectionAbove,
  onAddSectionBelow,
}) => {
  const onAddWidgetCallback = useDashboardStore(
    (state) => state.onAddWidgetCallback,
  );

  const [showDeleteDialog, setShowDeleteDialog] = useState(false);

  const handleAddWidget = useCallback(
    (e: React.MouseEvent) => {
      e.stopPropagation();
      onAddWidgetCallback?.(sectionId);
    },
    [onAddWidgetCallback, sectionId],
  );

  const handleDeleteClick = useCallback(
    (e: React.MouseEvent) => {
      e.stopPropagation();
      if (!isLastSection) {
        setShowDeleteDialog(true);
      }
    },
    [isLastSection],
  );

  return (
    <div className="flex size-full items-center gap-1">
      <div className="flex items-center">
        {expanded ? (
          <ChevronDown className="size-4 text-light-slate" />
        ) : (
          <ChevronRight className="size-4 text-light-slate" />
        )}
      </div>

      <DashboardSectionTitle
        title={title}
        widgetCount={widgetCount}
        onChange={onUpdateTitle}
      />

      <div
        className={
          expanded
            ? "flex items-center gap-2"
            : "hidden items-center gap-2 group-hover:flex"
        }
      >
        <Button
          onClick={(e) => {
            e.stopPropagation();
            handleAddWidget(e);
          }}
          className="h-8 gap-1.5 rounded-md bg-primary px-3 py-1.5 text-sm font-medium text-white hover:bg-primary/90"
        >
          Add widget
        </Button>

        <DropdownMenu>
          <DropdownMenuTrigger asChild>
            <Button
              variant="outline"
              size="icon"
              className="size-8 rounded-md border-muted bg-primary-100"
              onClick={(e) => e.stopPropagation()}
            >
              <MoreVertical className="size-3.5" />
            </Button>
          </DropdownMenuTrigger>
          <DropdownMenuContent align="end">
            <DropdownMenuItem
              onClick={(e) => {
                e.stopPropagation();
                onAddSectionAbove();
              }}
            >
              <ArrowUp className="mr-2 size-4" />
              Insert section above
            </DropdownMenuItem>
            <DropdownMenuItem
              onClick={(e) => {
                e.stopPropagation();
                onAddSectionBelow();
              }}
            >
              <ArrowDown className="mr-2 size-4" />
              Insert section below
            </DropdownMenuItem>
            <DropdownMenuSeparator />
            <DropdownMenuItem
              onClick={handleDeleteClick}
              disabled={isLastSection}
              className="text-destructive focus:text-destructive disabled:cursor-not-allowed disabled:opacity-50"
            >
              <Trash2 className="mr-2 size-4" />
              Delete section
            </DropdownMenuItem>
          </DropdownMenuContent>
        </DropdownMenu>

        <div className="flex h-full items-center px-1">
          <div className="h-6 w-px bg-muted" />
        </div>
      </div>

      <div
        {...dragHandleProps}
        onClick={(e) => e.stopPropagation()}
        className={
          expanded
            ? "flex size-8 cursor-grab items-center justify-center rounded-md border border-muted bg-background text-foreground transition-colors hover:bg-muted active:cursor-grabbing"
            : "hidden size-8 cursor-grab items-center justify-center rounded-md border border-muted bg-background text-foreground transition-colors hover:bg-muted active:cursor-grabbing group-hover:flex"
        }
      >
        <GripHorizontal className="size-3.5" />
      </div>

      <ConfirmDialog
        open={showDeleteDialog}
        setOpen={setShowDeleteDialog}
        onConfirm={onDeleteSection}
        title={`Delete ${title} section?`}
        description={`The '${title}' section will be permanently deleted and cannot be recovered.`}
        confirmText="Yes, delete"
        confirmButtonVariant="destructive"
      />
    </div>
  );
};

export default DashboardSectionHeader;
