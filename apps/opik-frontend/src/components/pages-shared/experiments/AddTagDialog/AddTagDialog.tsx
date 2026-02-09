import React, { useCallback } from "react";
import { Experiment } from "@/types/datasets";
import useExperimentBatchUpdateMutation from "@/api/datasets/useExperimentBatchUpdateMutation";
import ManageTagsDialog from "@/components/shared/ManageTagsDialog/ManageTagsDialog";

type AddTagDialogProps = {
  experiments: Experiment[];
  open: boolean | number;
  setOpen: (open: boolean | number) => void;
  onSuccess?: () => void;
};

const AddTagDialog: React.FunctionComponent<AddTagDialogProps> = ({
  experiments,
  open,
  setOpen,
  onSuccess,
}) => {
  const experimentBatchUpdateMutation = useExperimentBatchUpdateMutation();

  const handleUpdate = useCallback(
    async (tagsToAdd: string[], tagsToRemove: string[]) => {
      const ids = experiments.map((exp) => exp.id);

      await experimentBatchUpdateMutation.mutateAsync({
        ids,
        experiment: { tagsToAdd, tagsToRemove },
      });

      if (onSuccess) {
        onSuccess();
      }
    },
    [experiments, experimentBatchUpdateMutation, onSuccess],
  );

  return (
    <ManageTagsDialog
      entities={experiments}
      open={open}
      setOpen={setOpen}
      onUpdate={handleUpdate}
      maxTagLength={100}
      maxTags={50}
    />
  );
};

export default AddTagDialog;
