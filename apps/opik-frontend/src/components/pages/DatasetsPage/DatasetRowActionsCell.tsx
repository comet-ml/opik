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
import { Dataset } from "@/types/datasets";
import useDatasetDeleteMutation from "@/api/datasets/useDatasetDeleteMutation";

export const DatasetRowActionsCell: React.FunctionComponent<
  CellContext<Dataset, unknown>
> = ({ row }) => {
  const resetKeyRef = useRef(0);
  const dataset = row.original;
  const [open, setOpen] = useState<boolean>(false);

  const datasetDeleteMutation = useDatasetDeleteMutation();

  const deleteDatasetHandler = useCallback(() => {
    datasetDeleteMutation.mutate({
      datasetId: dataset.id,
    });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [dataset.id]);

  return (
    <div
      className="flex size-full items-center justify-end"
      onClick={(e) => e.stopPropagation()}
    >
      <ConfirmDialog
        key={`delete-${resetKeyRef.current}`}
        open={open}
        setOpen={setOpen}
        onConfirm={deleteDatasetHandler}
        title={`Delete ${dataset.name}`}
        description="Are you sure you want to delete this dataset?"
        confirmText="Delete dataset"
      />
      <DropdownMenu>
        <DropdownMenuTrigger asChild>
          <Button variant="minimal" size="icon">
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
    </div>
  );
};
