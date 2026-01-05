import React, { useMemo } from "react";

import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { DatasetVersion } from "@/types/datasets";
import useEditDatasetVersionMutation from "@/api/datasets/useEditDatasetVersionMutation";
import VersionForm, { VersionFormData } from "./VersionForm";

const EDIT_VERSION_FORM_ID = "edit-version-form";

type EditVersionDialogProps = {
  open: boolean;
  setOpen: (open: boolean) => void;
  version: DatasetVersion;
  datasetId: string;
};

const EditVersionDialog: React.FC<EditVersionDialogProps> = ({
  open,
  setOpen,
  version,
  datasetId,
}) => {
  const editMutation = useEditDatasetVersionMutation();

  // All existing tags are immutable - user can only add new tags
  const existingTags = useMemo(() => version.tags || [], [version.tags]);

  const initialValues = useMemo(
    () => ({
      versionNote: version.change_description || "",
      tags: [], // Empty - only tracks newly added tags
    }),
    [version.change_description],
  );

  const handleSubmit = (data: VersionFormData) => {
    editMutation.mutate(
      {
        datasetId,
        versionHash: version.version_hash,
        changeDescription: data.versionNote,
        tagsToAdd: data.tags.length > 0 ? data.tags : undefined,
      },
      {
        onSuccess: () => {
          setOpen(false);
        },
      },
    );
  };

  const handleCancel = () => {
    setOpen(false);
  };

  const handleOpenChange = (newOpen: boolean) => {
    setOpen(newOpen);
  };

  return (
    <Dialog open={open} onOpenChange={handleOpenChange}>
      <DialogContent className="max-w-lg">
        <DialogHeader>
          <DialogTitle>Edit version</DialogTitle>
        </DialogHeader>

        <p className="text-sm text-muted-foreground">
          Edit the version note and tags to keep your dataset versions
          organized.
        </p>

        <VersionForm
          id={EDIT_VERSION_FORM_ID}
          initialValues={initialValues}
          onSubmit={handleSubmit}
          immutableTags={existingTags}
        />

        <DialogFooter className="gap-3 border-t pt-6 md:gap-0">
          <Button
            type="button"
            variant="outline"
            onClick={handleCancel}
            disabled={editMutation.isPending}
          >
            Cancel
          </Button>
          <Button
            type="submit"
            form={EDIT_VERSION_FORM_ID}
            disabled={editMutation.isPending}
          >
            Update version
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
};

export default EditVersionDialog;
