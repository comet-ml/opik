import React, { useCallback } from "react";
import { PromptVersion } from "@/types/prompts";
import usePromptVersionsUpdateMutation from "@/api/prompts/usePromptVersionsUpdateMutation";
import ManageTagsDialog from "@/components/shared/ManageTagsDialog/ManageTagsDialog";

type AddTagDialogProps = {
  rows: Array<PromptVersion>;
  open: boolean | number;
  setOpen: (open: boolean | number) => void;
  onSuccess?: () => void;
};

const AddTagDialog: React.FunctionComponent<AddTagDialogProps> = ({
  rows,
  open,
  setOpen,
  onSuccess,
}) => {
  const updateMutation = usePromptVersionsUpdateMutation();

  const handleUpdate = useCallback(
    async (tagsToAdd: string[], tagsToRemove: string[]) => {
      if (tagsToRemove.length === 0 && tagsToAdd.length > 0) {
        const versionIds = rows.map((row) => row.id);
        await updateMutation.mutateAsync({
          versionIds,
          tags: tagsToAdd,
          mergeTags: true,
        });
      } else if (tagsToRemove.length > 0) {
        await Promise.all(
          rows.map((row) => {
            const currentTags = row?.tags || [];
            const finalTags = [
              ...currentTags.filter((t) => !tagsToRemove.includes(t)),
              ...tagsToAdd,
            ];

            return updateMutation.mutateAsync({
              versionIds: [row.id],
              tags: finalTags,
              mergeTags: false,
            });
          }),
        );
      }

      if (onSuccess) {
        onSuccess();
      }
    },
    [rows, updateMutation, onSuccess],
  );

  return (
    <ManageTagsDialog
      entities={rows}
      open={open}
      setOpen={setOpen}
      onUpdate={handleUpdate}
    />
  );
};

export default AddTagDialog;
