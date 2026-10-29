import React from "react";

import ConfirmDialog from "@/shared/ConfirmDialog/ConfirmDialog";
import useRestoreDatasetVersionMutation from "@/api/datasets/useRestoreDatasetVersionMutation";
import { useClearDraft, useHasDraft } from "@/store/TestSuiteDraftStore";
import { DatasetVersion } from "@/types/datasets";

type RestoreVersionDialogProps = {
  open: boolean;
  setOpen: (open: boolean) => void;
  datasetId: string;
  version: DatasetVersion;
  onRestored?: () => void;
};

const RestoreVersionDialog: React.FC<RestoreVersionDialogProps> = ({
  open,
  setOpen,
  datasetId,
  version,
  onRestored,
}) => {
  const restoreMutation = useRestoreDatasetVersionMutation();
  const hasDraft = useHasDraft();
  const clearDraft = useClearDraft();

  const handleRestore = () => {
    restoreMutation.mutate(
      { datasetId, versionRef: version.version_hash },
      {
        onSuccess: () => {
          clearDraft();
          onRestored?.();
        },
      },
    );
    setOpen(false);
  };

  return (
    <ConfirmDialog
      open={open}
      setOpen={setOpen}
      onConfirm={handleRestore}
      title="Restore version"
      description={
        `Restoring this version will create a new version based on version ${version.version_name}. All previous versions will stay in your history.` +
        (hasDraft
          ? "\n\nYou have unsaved draft changes that will be discarded. This action can't be undone."
          : "")
      }
      confirmText={hasDraft ? "Discard & Restore" : "Restore version"}
      confirmButtonVariant={hasDraft ? "destructive" : "default"}
    />
  );
};

export default RestoreVersionDialog;
