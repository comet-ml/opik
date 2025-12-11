import React, { useCallback, useRef, useState } from "react";
import { CellContext } from "@tanstack/react-table";
import { MoreHorizontal, Pencil, Copy, Trash } from "lucide-react";

import { Button } from "@/components/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import ConfirmDialog from "@/components/shared/ConfirmDialog/ConfirmDialog";
import { Dashboard } from "@/types/dashboard";
import useDashboardBatchDeleteMutation from "@/api/dashboards/useDashboardBatchDeleteMutation";
import AddEditCloneDashboardDialog from "@/components/pages-shared/dashboards/AddEditCloneDashboardDialog/AddEditCloneDashboardDialog";

export const DashboardRowActionsCell: React.FunctionComponent<
  CellContext<Dashboard, unknown>
> = (context) => {
  const resetKeyRef = useRef(0);
  const dashboard = context.row.original;

  const { mutate: deleteDashboardMutate } = useDashboardBatchDeleteMutation();

  const [open, setOpen] = useState<boolean>(false);
  const [openEdit, setOpenEdit] = useState<boolean>(false);
  const [openClone, setOpenClone] = useState<boolean>(false);
  const [openConfirmDialog, setOpenConfirmDialog] = useState<boolean>(false);

  const deleteDashboard = useCallback(() => {
    deleteDashboardMutate({
      ids: [dashboard.id],
    });
  }, [dashboard, deleteDashboardMutate]);

  const handleEdit = useCallback(() => {
    setOpenEdit(true);
    setOpen(false);
    resetKeyRef.current = resetKeyRef.current + 1;
  }, []);

  const handleClone = useCallback(() => {
    setOpenClone(true);
    setOpen(false);
    resetKeyRef.current = resetKeyRef.current + 1;
  }, []);

  const handleDelete = useCallback(() => {
    setOpenConfirmDialog(true);
    setOpen(false);
  }, []);

  return (
    <div
      className="flex items-center justify-end gap-2"
      onClick={(e) => e.stopPropagation()}
    >
      <DropdownMenu open={open} onOpenChange={setOpen}>
        <DropdownMenuTrigger asChild>
          <Button variant="minimal" size="icon">
            <span className="sr-only">Actions menu</span>
            <MoreHorizontal className="size-4" />
          </Button>
        </DropdownMenuTrigger>
        <DropdownMenuContent align="end" className="w-52">
          <DropdownMenuItem onClick={handleEdit}>
            <Pencil className="mr-2 size-4" />
            Edit
          </DropdownMenuItem>
          <DropdownMenuItem onClick={handleClone}>
            <Copy className="mr-2 size-4" />
            Clone
          </DropdownMenuItem>
          <DropdownMenuItem onClick={handleDelete}>
            <Trash className="mr-2 size-4" />
            Delete
          </DropdownMenuItem>
        </DropdownMenuContent>
      </DropdownMenu>
      <AddEditCloneDashboardDialog
        mode="edit"
        key={`edit-${resetKeyRef.current}`}
        dashboard={dashboard}
        open={openEdit}
        setOpen={setOpenEdit}
      />
      <AddEditCloneDashboardDialog
        mode="clone"
        key={`clone-${resetKeyRef.current}`}
        dashboard={dashboard}
        open={openClone}
        setOpen={setOpenClone}
      />
      <ConfirmDialog
        key={`delete-${resetKeyRef.current}`}
        open={openConfirmDialog}
        setOpen={setOpenConfirmDialog}
        onConfirm={deleteDashboard}
        title="Delete dashboard"
        description="Are you sure you want to delete this dashboard?"
        confirmText="Delete dashboard"
      />
    </div>
  );
};
