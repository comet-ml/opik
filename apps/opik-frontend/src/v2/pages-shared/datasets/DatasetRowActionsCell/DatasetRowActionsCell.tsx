import React, { useCallback, useRef, useState } from "react";
import { CellContext } from "@tanstack/react-table";
import { Download, Pencil, Trash } from "lucide-react";

import { useToast } from "@/ui/use-toast";
import useDatasetDeleteMutation from "@/api/datasets/useDatasetDeleteMutation";
import useStartDatasetExportMutation from "@/api/datasets/useStartDatasetExportMutation";
import ConfirmDialog from "@/shared/ConfirmDialog/ConfirmDialog";
import RowActions, { RowAction } from "@/shared/DataTable/RowActions";
import useIsDatasetExportEnabled from "@/hooks/useIsDatasetExportEnabled";
import {
  useAddExportJob,
  useSetPanelExpanded,
} from "@/store/DatasetExportStore";
import { Dataset } from "@/types/datasets";
import { usePermissions } from "@/contexts/PermissionsContext";

type CreateDatasetRowActionsCellConfig = {
  entityName: string;
  EditDialog: React.ComponentType<{
    open: boolean;
    setOpen: (open: boolean) => void;
    dataset?: Dataset;
  }>;
};

export const createDatasetRowActionsCell = ({
  entityName,
  EditDialog,
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
    const isDatasetExportEnabled = useIsDatasetExportEnabled();

    const {
      permissions: { canEditDatasets, canDeleteDatasets },
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
                "Failed to start test suite export. Please try again.",
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

    const openDialog = (dialog: number) => {
      setOpen(dialog);
      resetKeyRef.current = resetKeyRef.current + 1;
    };
    const rowActions: RowAction[] = [
      ...(canEditDatasets
        ? [
            {
              id: "edit",
              label: "Edit",
              icon: Pencil,
              onClick: () => openDialog(2),
            },
          ]
        : []),
      ...(isDatasetExportEnabled
        ? [
            {
              id: "download",
              label: "Download",
              icon: Download,
              onClick: downloadDatasetHandler,
              disabled: isExportStarting,
              disabledReason: "Export in progress",
            },
          ]
        : []),
      ...(canDeleteDatasets
        ? [
            {
              id: "delete",
              label: "Delete",
              icon: Trash,
              onClick: () => openDialog(1),
              destructive: true,
            },
          ]
        : []),
    ];

    return (
      <>
        <EditDialog
          key={`edit-${dataset.id}`}
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
            description={`Deleting this ${entityName} will also remove all its items. Any experiments linked to it will be moved to a \u201cDeleted ${entityName}\u201d group. This action can\u2019t be undone. Are you sure you want to continue?`}
            confirmText={`Delete ${entityName}`}
            confirmButtonVariant="destructive"
          />
        )}
        <RowActions actions={rowActions} />
      </>
    );
  };

  return DatasetRowActionsCell;
};
