import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { Button } from "@/components/ui/button";
import { MoreHorizontal, Trash } from "lucide-react";
import React, { useCallback } from "react";
import { CellContext } from "@tanstack/react-table";
import { DatasetItem } from "@/types/datasets";
import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";
import { useDeleteItem } from "@/store/DatasetDraftStore";

export const DatasetItemRowActionsCell: React.FunctionComponent<
  CellContext<DatasetItem, unknown>
> = (context) => {
  const datasetItem = context.row.original;

  // Draft store actions
  const deleteItem = useDeleteItem();

  const deleteDataset = useCallback(() => {
    deleteItem(datasetItem.id);
  }, [datasetItem.id, deleteItem]);

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
      className="justify-end p-0"
      stopClickPropagation
    >
      <DropdownMenu>
        <DropdownMenuTrigger asChild>
          <Button variant="minimal" size="icon" className="-mr-2.5">
            <span className="sr-only">Actions menu</span>
            <MoreHorizontal className="size-4" />
          </Button>
        </DropdownMenuTrigger>
        <DropdownMenuContent align="end" className="w-52">
          <DropdownMenuItem onClick={deleteDataset} variant="destructive">
            <Trash className="mr-2 size-4" />
            Delete
          </DropdownMenuItem>
        </DropdownMenuContent>
      </DropdownMenu>
    </CellWrapper>
  );
};
