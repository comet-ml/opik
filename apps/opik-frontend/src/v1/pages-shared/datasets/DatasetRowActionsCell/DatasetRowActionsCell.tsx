import React, { useCallback, useRef, useState } from "react";
import { CellContext } from "@tanstack/react-table";
import { Download, MoreHorizontal, Pencil, Trash } from "lucide-react";

import { Button } from "@/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/ui/dropdown-menu";
import { useToast } from "@/ui/use-toast";
import useDatasetDeleteMutation from "@/api/datasets/useDatasetDeleteMutation";
import useStartDatasetExportMutation from "@/api/datasets/useStartDatasetExportMutation";
import ConfirmDialog from "@/shared/ConfirmDialog/ConfirmDialog";
import CellWrapper from "@/shared/DataTableCells/CellWrapper";
import { useIsFeatureEnabled } from "@/v1/feature-toggles-provider";
import {
  useAddExportJob,
  useSetPanelExpanded,
} from "@/store/DatasetExportStore";
import { Dataset } from "@/types/datasets";
import { FeatureToggleKeys } from "@/types/feature-toggles";
import { usePermissions } from "@/contexts/PermissionsContext";

type CreateDatasetRowActionsCellConfig = {
  entityName: string;
  EditDialog: React.ComponentType<{
    open: boolean;
    setOpen: (open: boolean) => void;
    dataset?: Dataset;
  }>;
  showDownload?: boolean;
};

export const createDatasetRowActionsCell = ({
  entityName,
  EditDialog,
  showDownload = false,
}: CreateDatasetRowActionsCellConfig): React.FunctionComponent<
  CellContext<Dataset, unknown>
> => {
  const DatasetRowActionsCell: React.FunctionComponent<
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

    const {
      permissions: { canDeleteDatasets },
    } = usePermissions();

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
              description:
                "Failed to start evaluation suite export. Please try again.",
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
        <EditDialog
          key={`add-${resetKeyRef.current}`}
          open={open === 2}
          setOpen={(val: boolean) => setOpen(val)}
          dataset={dataset}
        />
        {canDeleteDatasets && (
          <ConfirmDialog
            key={`delete-${resetKeyRef.current}`}
            open={open === 1}
            setOpen={setOpen}
            onConfirm={deleteDatasetHandler}
            title={`Delete ${entityName}`}
            description={`Deleting this ${entityName} will also remove all its items. Any experiments linked to it will be moved to a \u201cDeleted evaluation suite\u201d group. This action can\u2019t be undone. Are you sure you want to continue?`}
            confirmText={`Delete ${entityName}`}
            confirmButtonVariant="destructive"
          />
        )}
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
            {showDownload && isDatasetExportEnabled && (
              <DropdownMenuItem
                onClick={downloadDatasetHandler}
                disabled={isExportStarting}
              >
                <Download className="mr-2 size-4" />
                Download
              </DropdownMenuItem>
            )}
            {canDeleteDatasets && (
              <>
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
              </>
            )}
          </DropdownMenuContent>
        </DropdownMenu>
      </CellWrapper>
    );
  };

  return DatasetRowActionsCell;
};
