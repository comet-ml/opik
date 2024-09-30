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
import { DatasetItem } from "@/types/datasets";
import { useDatasetIdFromURL } from "@/hooks/useDatasetIdFromURL";
import useAppStore from "@/store/AppStore";
import useDatasetItemDeleteMutation from "@/api/datasets/useDatasetItemDeleteMutation";
import ConfirmDialog from "@/components/shared/ConfirmDialog/ConfirmDialog";

export const DatasetItemRowActionsCell: React.FunctionComponent<
  CellContext<DatasetItem, unknown>
> = ({ row }) => {
  const datasetId = useDatasetIdFromURL();
  const resetKeyRef = useRef(0);
  const datasetItem = row.original;
  const [open, setOpen] = useState<boolean | number>(false);

  const workspaceName = useAppStore((state) => state.activeWorkspaceName);

  const datasetItemDeleteMutation = useDatasetItemDeleteMutation();

  const deleteDataset = useCallback(() => {
    datasetItemDeleteMutation.mutate({
      datasetId,
      datasetItemId: datasetItem.id,
      workspaceName,
    });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [datasetItem.id, datasetId, workspaceName]);

  return (
    <div
      className="flex size-full items-center justify-end"
      onClick={(e) => e.stopPropagation()}
    >
      <ConfirmDialog
        key={`delete-${resetKeyRef.current}`}
        open={open === 1}
        setOpen={setOpen}
        onConfirm={deleteDataset}
        title="Delete dataset item"
        description="Are you sure you want to delete this dataset item?"
        confirmText="Delete dataset item"
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
              setOpen(1);
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
