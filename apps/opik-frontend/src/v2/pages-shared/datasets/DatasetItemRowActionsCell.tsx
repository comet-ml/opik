import React, { useCallback, useState } from "react";
import { CellContext } from "@tanstack/react-table";
import { MoreHorizontal, Pencil, Trash } from "lucide-react";

import { Button } from "@/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/ui/dropdown-menu";
import CellWrapper from "@/shared/DataTableCells/CellWrapper";
import { DatasetItem } from "@/types/datasets";
import { useDeleteItem } from "@/store/TestSuiteDraftStore";
import RemoveDatasetItemsDialog from "./RemoveDatasetItemsDialog";
import { useDatasetItemDeletePreference } from "./hooks/useDatasetItemDeletePreference";

type CustomMeta = {
  setActiveRowId?: (id: string) => void;
};

export const DatasetItemRowActionsCell: React.FC<
  CellContext<DatasetItem, unknown>
> = (context) => {
  const datasetItem = context.row.original;
  const [confirmOpen, setConfirmOpen] = useState(false);
  const [dontAskAgain] = useDatasetItemDeletePreference();
  const deleteItem = useDeleteItem();

  const { custom } = context.column.columnDef.meta ?? {};
  const { setActiveRowId } = (custom ?? {}) as CustomMeta;

  const performDelete = useCallback(() => {
    deleteItem(datasetItem.id);
  }, [datasetItem.id, deleteItem]);

  const handleDeleteClick = useCallback(() => {
    if (dontAskAgain) {
      performDelete();
    } else {
      setConfirmOpen(true);
    }
  }, [dontAskAgain, performDelete]);

  const handleEditClick = useCallback(() => {
    setActiveRowId?.(datasetItem.id);
  }, [datasetItem.id, setActiveRowId]);

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
      className="justify-end p-0"
      stopClickPropagation
    >
      <RemoveDatasetItemsDialog
        open={confirmOpen}
        setOpen={setConfirmOpen}
        onConfirm={performDelete}
      />
      <DropdownMenu>
        <DropdownMenuTrigger asChild>
          <Button variant="minimal" size="icon" className="-mr-2.5">
            <span className="sr-only">Actions menu</span>
            <MoreHorizontal className="size-4" />
          </Button>
        </DropdownMenuTrigger>
        <DropdownMenuContent align="end" className="w-52">
          {setActiveRowId && (
            <DropdownMenuItem onClick={handleEditClick}>
              <Pencil className="mr-2 size-4" />
              Edit
            </DropdownMenuItem>
          )}
          {setActiveRowId && <DropdownMenuSeparator />}
          <DropdownMenuItem onClick={handleDeleteClick} variant="destructive">
            <Trash className="mr-2 size-4" />
            Delete
          </DropdownMenuItem>
        </DropdownMenuContent>
      </DropdownMenu>
    </CellWrapper>
  );
};
