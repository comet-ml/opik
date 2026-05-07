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

const EnvironmentsRowActionsCell: React.FunctionComponent<
  CellContext<Environment, unknown>
> = (context) => {
  const resetKeyRef = useRef(0);
  const environment = context.row.original;
  const [open, setOpen] = useState<boolean | number>(false);

  const deleteMutation = useEnvironmentBatchDeleteMutation();

  const deleteHandler = useCallback(() => {
    deleteMutation.mutate({ ids: [environment.id] });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [environment.id]);

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
        open={open === 2 || open === 3}
        setOpen={setOpen}
        mode={open === 2 ? "edit" : "clone"}
      />
      <ConfirmDialog
        key={`delete-${resetKeyRef.current}`}
        open={open === 1}
        setOpen={setOpen}
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
          <DropdownMenuItem
            onClick={() => {
              setOpen(2);
              resetKeyRef.current = resetKeyRef.current + 1;
            }}
          >
            <Pencil className="mr-2 size-4" />
            Edit
          </DropdownMenuItem>
          <DropdownMenuItem
            onClick={() => {
              setOpen(3);
              resetKeyRef.current = resetKeyRef.current + 1;
            }}
          >
            <Copy className="mr-2 size-4" />
            Clone
          </DropdownMenuItem>
          <DropdownMenuSeparator />
          <DropdownMenuItem
            onClick={() => {
              setOpen(1);
              resetKeyRef.current = resetKeyRef.current + 1;
            }}
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
