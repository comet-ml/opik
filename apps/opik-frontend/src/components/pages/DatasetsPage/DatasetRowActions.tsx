import React, { useCallback } from "react";
import { Download } from "lucide-react";
import AddEditDatasetDialog from "@/components/pages/DatasetsPage/AddEditDatasetDialog";
import ConfirmDialog from "@/components/shared/ConfirmDialog/ConfirmDialog";
import { Dataset } from "@/types/datasets";
import useDatasetDeleteMutation from "@/api/datasets/useDatasetDeleteMutation";
import useStartDatasetExportMutation from "@/api/datasets/useStartDatasetExportMutation";
import {
  useAddExportJob,
  useSetPanelExpanded,
} from "@/store/DatasetExportStore";
import { useRowActionsState } from "@/components/shared/DataTable/hooks/useRowActionsState";
import { RowActionsButtons } from "@/components/shared/DataTable/RowActionsButtons";
import { useToast } from "@/components/ui/use-toast";
import { useIsFeatureEnabled } from "@/components/feature-toggles-provider";
import { FeatureToggleKeys } from "@/types/feature-toggles";

type DatasetRowActionsProps = {
  dataset: Dataset;
};

export const DatasetRowActions: React.FC<DatasetRowActionsProps> = ({
  dataset,
}) => {
  const { dialogOpen, open, close } = useRowActionsState();
  const { mutate } = useDatasetDeleteMutation();
  const { mutate: startExport, isPending: isExportStarting } =
    useStartDatasetExportMutation();
  const addExportJob = useAddExportJob();
  const setPanelExpanded = useSetPanelExpanded();
  const { toast } = useToast();
  const isDatasetExportEnabled = useIsFeatureEnabled(
    FeatureToggleKeys.DATASET_EXPORT_ENABLED,
  );

  const handleDelete = () => {
    mutate({ datasetId: dataset.id });
  };

  const handleDownload = useCallback(() => {
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
    <>
      <ConfirmDialog
        open={dialogOpen === "delete"}
        setOpen={close}
        onConfirm={handleDelete}
        title="Delete dataset"
        description="Deleting this dataset will also remove all its items. Any experiments linked to it will be moved to a 'Deleted dataset' group. This action can't be undone. Are you sure you want to continue?"
        confirmText="Delete dataset"
        confirmButtonVariant="destructive"
      />
      <AddEditDatasetDialog
        dataset={dataset}
        open={dialogOpen === "edit"}
        setOpen={close}
      />
      <RowActionsButtons
        actions={[
          { type: "edit", onClick: open("edit") },
          ...(isDatasetExportEnabled
            ? [
                {
                  type: "download" as const,
                  onClick: handleDownload,
                  disabled: isExportStarting,
                },
              ]
            : []),
          { type: "delete", onClick: open("delete") },
        ]}
      />
    </>
  );
};
