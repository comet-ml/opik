import { useCallback, useState } from "react";
import { CellContext } from "@tanstack/react-table";
import { MoreHorizontal, Trash } from "lucide-react";

import { Button } from "@/components/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";
import { DatasetItem } from "@/types/datasets";
import { useDeleteItem } from "@/store/EvaluationSuiteDraftStore";
import RemoveDatasetItemsDialog from "./RemoveDatasetItemsDialog";
import { useDatasetItemDeletePreference } from "./hooks/useDatasetItemDeletePreference";

export const DatasetItemRowActionsCell = (
  context: CellContext<DatasetItem, unknown>,
) => {
  const datasetItem = context.row.original;
  const [confirmOpen, setConfirmOpen] = useState(false);
  const [dontAskAgain] = useDatasetItemDeletePreference();
  const deleteItem = useDeleteItem();

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
          <DropdownMenuItem onClick={handleDeleteClick} variant="destructive">
            <Trash className="mr-2 size-4" />
            Delete
          </DropdownMenuItem>
        </DropdownMenuContent>
      </DropdownMenu>
    </CellWrapper>
  );
};
