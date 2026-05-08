import React, { useCallback, useRef, useState } from "react";
import { CellContext } from "@tanstack/react-table";
import { Copy, MoreHorizontal, Pencil, Trash } from "lucide-react";

import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/ui/dropdown-menu";
import { Button } from "@/ui/button";
import { Environment } from "@/types/environments";
import ConfirmDialog from "@/shared/ConfirmDialog/ConfirmDialog";
import useEnvironmentBatchDeleteMutation from "@/api/environments/useEnvironmentBatchDeleteMutation";
import AddEditEnvironmentDialog from "@/v2/pages-shared/environments/AddEditEnvironmentDialog/AddEditEnvironmentDialog";
import CellWrapper from "@/shared/DataTableCells/CellWrapper";

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

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
      className="justify-end p-0"
      stopClickPropagation
    >
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
        description="This action can’t be undone. Existing traces and spans will keep their environment value and surface under “Unrecognized environments”. Are you sure you want to continue?"
        confirmText="Delete environment"
        confirmButtonVariant="destructive"
      />
      <DropdownMenu>
        <DropdownMenuTrigger asChild>
          <Button variant="minimal" size="icon" className="-mr-2.5">
            <span className="sr-only">Actions menu</span>
            <MoreHorizontal className="size-4" />
          </Button>
        </DropdownMenuTrigger>
        <DropdownMenuContent align="end" className="w-52">
          <DropdownMenuItem onClick={() => openDialog("edit")}>
            <Pencil className="mr-2 size-4" />
            Edit
          </DropdownMenuItem>
          <DropdownMenuItem onClick={() => openDialog("clone")}>
            <Copy className="mr-2 size-4" />
            Clone
          </DropdownMenuItem>
          <DropdownMenuSeparator />
          <DropdownMenuItem
            onClick={() => openDialog("delete")}
            variant="destructive"
          >
            <Trash className="mr-2 size-4" />
            Delete
          </DropdownMenuItem>
        </DropdownMenuContent>
      </DropdownMenu>
    </CellWrapper>
  );
};

export default EnvironmentsRowActionsCell;
