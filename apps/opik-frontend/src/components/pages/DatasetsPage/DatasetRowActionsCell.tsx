import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { Button } from "@/components/ui/button";
import { MoreHorizontal, Pencil, Trash } from "lucide-react";
import React, { useCallback, useRef, useState } from "react";
import { CellContext } from "@tanstack/react-table";
import AddEditDatasetDialog from "@/components/pages/DatasetsPage/AddEditDatasetDialog";
import ConfirmDialog from "@/components/shared/ConfirmDialog/ConfirmDialog";
import { Dataset } from "@/types/datasets";
import useDatasetDeleteMutation from "@/api/datasets/useDatasetDeleteMutation";
import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";

export const DatasetRowActionsCell: React.FunctionComponent<
  CellContext<Dataset, unknown>
> = (context) => {
  const resetKeyRef = useRef(0);
  const dataset = context.row.original;
  const [open, setOpen] = useState<boolean | number>(false);

  const { mutate } = useDatasetDeleteMutation();

  const deleteDatasetHandler = useCallback(() => {
    mutate({
      datasetId: dataset.id,
    });
  }, [dataset.id, mutate]);

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
      className="justify-end p-0"
      stopClickPropagation
    >
      <AddEditDatasetDialog
        key={`add-${resetKeyRef.current}`}
        open={open === 2}
        setOpen={setOpen}
        dataset={dataset}
      />
      <ConfirmDialog
        key={`delete-${resetKeyRef.current}`}
        open={open === 1}
        setOpen={setOpen}
        onConfirm={deleteDatasetHandler}
        title="Delete dataset"
        description="Deleting this dataset will also remove all its items. Any experiments linked to it will be moved to a “Deleted dataset” group. This action can’t be undone. Are you sure you want to continue?"
        confirmText="Delete dataset"
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
              setOpen(1);
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
