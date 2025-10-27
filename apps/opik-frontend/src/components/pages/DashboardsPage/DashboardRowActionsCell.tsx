import React, { useCallback, useState } from "react";
import { CellContext } from "@tanstack/react-table";
import { MoreHorizontal, Pencil, Trash } from "lucide-react";

import { Button } from "@/components/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { Dashboard } from "@/types/dashboards";
import ConfirmDialog from "@/components/shared/ConfirmDialog/ConfirmDialog";
import useDashboardDeleteMutation from "@/api/dashboards/useDashboardDeleteMutation";
import useAppStore from "@/store/AppStore";
import AddEditDashboardDialog from "./AddEditDashboardDialog";

export const DashboardRowActionsCell: React.FunctionComponent<
  CellContext<Dashboard, unknown>
> = ({ row }) => {
  const dashboard = row.original;
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);

  const [openEditDialog, setOpenEditDialog] = useState(false);
  const [openDeleteDialog, setOpenDeleteDialog] = useState(false);

  const deleteMutation = useDashboardDeleteMutation();

  const handleDelete = useCallback(() => {
    deleteMutation.mutate({
      dashboardId: dashboard.id,
      workspaceName,
    });
    setOpenDeleteDialog(false);
  }, [deleteMutation, dashboard.id, workspaceName]);

  return (
    <div onClick={(e) => e.stopPropagation()}>
      <DropdownMenu>
        <DropdownMenuTrigger asChild>
          <Button variant="ghost" size="icon">
            <span className="sr-only">Actions menu</span>
            <MoreHorizontal className="size-4" />
          </Button>
        </DropdownMenuTrigger>
        <DropdownMenuContent align="end">
          <DropdownMenuItem onClick={() => setOpenEditDialog(true)}>
            <Pencil className="mr-2 size-4" />
            Edit
          </DropdownMenuItem>
          <DropdownMenuItem
            onClick={() => setOpenDeleteDialog(true)}
            disabled={dashboard.type === "prebuilt"}
          >
            <Trash className="mr-2 size-4" />
            Delete
          </DropdownMenuItem>
        </DropdownMenuContent>
      </DropdownMenu>

      <AddEditDashboardDialog
        open={openEditDialog}
        setOpen={setOpenEditDialog}
        dashboardId={dashboard.id}
      />

      <ConfirmDialog
        open={openDeleteDialog}
        onOpenChange={setOpenDeleteDialog}
        onConfirm={handleDelete}
        title="Delete dashboard"
        description={`Are you sure you want to delete "${dashboard.name}"? This action cannot be undone.`}
        confirmText="Delete"
      />
    </div>
  );
};



