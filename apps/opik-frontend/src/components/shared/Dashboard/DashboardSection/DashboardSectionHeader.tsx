import React, { useCallback, useState } from "react";
import {
  GripHorizontal,
  MoreHorizontal,
  Trash,
  ArrowUp,
  ArrowDown,
  ChevronDown,
  ChevronRight,
  Plus,
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
import { cn } from "@/lib/utils";

interface DashboardSectionHeaderProps {
  sectionId: string;
  title: string;
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
  expanded,
  isLastSection,
  dragHandleProps,
  onUpdateTitle,
  onDeleteSection,
  onAddSectionAbove,
  onAddSectionBelow,
}) => {
  const onAddEditWidgetCallback = useDashboardStore(
    (state) => state.onAddEditWidgetCallback,
  );

  const [showDeleteDialog, setShowDeleteDialog] = useState(false);
  const [menuOpen, setMenuOpen] = useState(false);

  const handleAddWidget = useCallback(
    (e: React.MouseEvent) => {
      e.stopPropagation();
      onAddEditWidgetCallback?.({ sectionId });
    },
    [onAddEditWidgetCallback, sectionId],
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

      <DashboardSectionTitle title={title} onChange={onUpdateTitle} />

      <div
        className={cn(
          "flex items-center gap-2",
          !expanded && !menuOpen && "hidden group-hover:flex",
        )}
      >
        <Button
          variant="outline"
          size="sm"
          onClick={(e) => {
            e.stopPropagation();
            handleAddWidget(e);
          }}
          className="gap-1.5"
        >
          <Plus className="size-3.5" />
          Add widget
        </Button>

        <DropdownMenu onOpenChange={setMenuOpen}>
          <DropdownMenuTrigger asChild>
            <Button
              variant="outline"
              size="icon-sm"
              onClick={(e) => e.stopPropagation()}
            >
              <MoreHorizontal />
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
              variant="destructive"
            >
              <Trash className="mr-2 size-4" />
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
        className={cn(
          "cursor-grab text-light-slate active:cursor-grabbing",
          !expanded && !menuOpen && "hidden group-hover:flex",
        )}
      >
        <GripHorizontal className="size-4" />
      </div>

      <ConfirmDialog
        open={showDeleteDialog}
        setOpen={setShowDeleteDialog}
        onConfirm={onDeleteSection}
        title={`Delete ${title} section?`}
        description={`This section will be removed from your dashboard. You can still undo this change before saving the dashboard.`}
        confirmText={`Delete ${title}`}
        confirmButtonVariant="destructive"
      />
    </div>
  );
};

export default DashboardSectionHeader;
