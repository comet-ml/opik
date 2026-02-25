import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { Button } from "@/components/ui/button";
import { Download, MoreHorizontal, Pencil, Trash } from "lucide-react";
import React, { useCallback, useRef, useState } from "react";
import { CellContext } from "@tanstack/react-table";
import AddEditDatasetDialog from "@/components/pages/DatasetsPage/AddEditDatasetDialog";
import ConfirmDialog from "@/components/shared/ConfirmDialog/ConfirmDialog";
import { Dataset } from "@/types/datasets";
import useDatasetDeleteMutation from "@/api/datasets/useDatasetDeleteMutation";
import useStartDatasetExportMutation from "@/api/datasets/useStartDatasetExportMutation";
import {
  useAddExportJob,
  useSetPanelExpanded,
} from "@/store/DatasetExportStore";
import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";
import { useToast } from "@/components/ui/use-toast";
import { useIsFeatureEnabled } from "@/components/feature-toggles-provider";
import { FeatureToggleKeys } from "@/types/feature-toggles";

export const DatasetRowActionsCell: React.FunctionComponent<
  CellContext<Dataset, unknown>
> = (context) => {
  const resetKeyRef = useRef(0);
  const dataset = context.row.original;
  const [open, setOpen] = useState<boolean | number>(false);

  const { mutate: deleteDataset } = useDatasetDeleteMutation();
  const { mutate: startExport, isPending: isExportStarting } =
    useStartDatasetExportMutation();
  const addExportJob = useAddExportJob();
  const setPanelExpanded = useSetPanelExpanded();
  const { toast } = useToast();
  const isDatasetExportEnabled = useIsFeatureEnabled(
    FeatureToggleKeys.DATASET_EXPORT_ENABLED,
  );

  const deleteDatasetHandler = useCallback(() => {
    deleteDataset({
      datasetId: dataset.id,
    });
  }, [dataset.id, deleteDataset]);

  const downloadDatasetHandler = useCallback(() => {
    startExport(
      { datasetId: dataset.id },
      {
        onSuccess: (job) => {
          addExportJob(job, dataset.name);
          setPanelExpanded(true);
        },
        onError: () => {
          toast({
            title: "Export failed",
            description: "Failed to start dataset export. Please try again.",
            variant: "destructive",
          });
        },
      },
    );
  }, [
    dataset.id,
    dataset.name,
    startExport,
    addExportJob,
    setPanelExpanded,
    toast,
  ]);

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
          {isDatasetExportEnabled && (
            <DropdownMenuItem
              onClick={downloadDatasetHandler}
              disabled={isExportStarting}
            >
              <Download className="mr-2 size-4" />
              Download
            </DropdownMenuItem>
          )}
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
