import React, { useCallback, useRef, useState } from "react";
import { CellContext } from "@tanstack/react-table";
import { Copy, Pencil, Trash } from "lucide-react";

import { Environment } from "@/types/environments";
import ConfirmDialog from "@/shared/ConfirmDialog/ConfirmDialog";
import useEnvironmentBatchDeleteMutation from "@/api/environments/useEnvironmentBatchDeleteMutation";
import AddEditEnvironmentDialog from "@/v2/pages-shared/environments/AddEditEnvironmentDialog/AddEditEnvironmentDialog";
import RowActions, { RowAction } from "@/shared/DataTable/RowActions";

type DialogState = "closed" | "edit" | "clone" | "delete";

const EnvironmentsRowActionsCell: React.FunctionComponent<
  CellContext<Environment, unknown>
> = (context) => {
  const resetKeyRef = useRef(0);
  const environment = context.row.original;
  const [dialog, setDialog] = useState<DialogState>("closed");

  const { mutate: deleteMutate } = useEnvironmentBatchDeleteMutation();

  const deleteHandler = useCallback(() => {
    deleteMutate({ ids: [environment.id] });
  }, [environment.id, deleteMutate]);

  const handleClose = useCallback((open: boolean) => {
    if (!open) setDialog("closed");
  }, []);

  const openDialog = useCallback((next: Exclude<DialogState, "closed">) => {
    setDialog(next);
    resetKeyRef.current += 1;
  }, []);

  const rowActions: RowAction[] = [
    {
      id: "edit",
      label: "Edit",
      icon: Pencil,
      onClick: () => openDialog("edit"),
    },
    {
      id: "clone",
      label: "Clone",
      icon: Copy,
      onClick: () => openDialog("clone"),
    },
    {
      id: "delete",
      label: "Delete",
      icon: Trash,
      onClick: () => openDialog("delete"),
      destructive: true,
    },
  ];

  return (
    <>
      <AddEditEnvironmentDialog
        key={`edit-${resetKeyRef.current}`}
        environment={environment}
        open={dialog === "edit" || dialog === "clone"}
        setOpen={handleClose}
        mode={dialog === "edit" ? "edit" : "clone"}
      />
      <ConfirmDialog
        key={`delete-${resetKeyRef.current}`}
        open={dialog === "delete"}
        setOpen={handleClose}
        onConfirm={deleteHandler}
        title="Delete environment"
        description="This action can’t be undone. Existing traces and spans will keep their environment value. Are you sure you want to continue?"
        confirmText="Delete environment"
        confirmButtonVariant="destructive"
      />
      <RowActions actions={rowActions} />
    </>
  );
};

export default EnvironmentsRowActionsCell;
