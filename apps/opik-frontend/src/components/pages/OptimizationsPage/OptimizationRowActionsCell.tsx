import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { Button } from "@/components/ui/button";
import { MoreHorizontal, Trash } from "lucide-react";
import React, { useCallback, useRef, useState } from "react";
import { CellContext } from "@tanstack/react-table";
import ConfirmDialog from "@/components/shared/ConfirmDialog/ConfirmDialog";
import useOptimizationBatchDeleteMutation from "@/api/optimizations/useOptimizationBatchDeleteMutation";
import { GroupedOptimization } from "@/hooks/useGroupedOptimizationsList";
import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";

const OptimizationRowActionsCell: React.FunctionComponent<
  CellContext<GroupedOptimization, unknown>
> = (context) => {
  const resetKeyRef = useRef(0);
  const organisation = context.row.original;
  const [open, setOpen] = useState<boolean>(false);

  const { mutate } = useOptimizationBatchDeleteMutation();

  const deleteOptimizationHandler = useCallback(() => {
    mutate({
      ids: [organisation.id],
    });
  }, [organisation, mutate]);

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
      className="justify-end p-0"
      stopClickPropagation
    >
      <ConfirmDialog
        key={`delete-${resetKeyRef.current}`}
        open={open}
        setOpen={setOpen}
        onConfirm={deleteOptimizationHandler}
        title={`Delete ${organisation.name}`}
        description="Are you sure you want to delete this optimization?"
        confirmText="Delete optimization"
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
              setOpen(true);
              resetKeyRef.current = resetKeyRef.current + 1;
            }}
          >
            <Trash className="mr-2 size-4" />
            Delete
          </DropdownMenuItem>
        </DropdownMenuContent>
      </DropdownMenu>
    </CellWrapper>
  );
};

export default OptimizationRowActionsCell;
