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
import {
  LATEST_VERSION_TAG,
  isLatestVersionTag,
  filterOutLatestTag,
} from "@/constants/datasets";
import VersionForm, { VersionFormData } from "./VersionForm";

const EDIT_VERSION_FORM_ID = "edit-version-form";

type EditVersionDialogProps = {
  open: boolean;
  setOpen: (open: boolean) => void;
  version: DatasetVersion;
};

const EditVersionDialog: React.FC<EditVersionDialogProps> = ({
  open,
  setOpen,
  version,
}) => {
  const isLatestVersion = version.tags?.some(isLatestVersionTag) ?? false;

  const initialValues = useMemo(
    () => ({
      versionNote: version.change_description || "",
      tags: filterOutLatestTag(version.tags || []),
    }),
    [version.change_description, version.tags],
  );

  const handleSubmit = (data: VersionFormData) => {
    console.log("Edit version form data:", {
      versionId: version.id,
      versionHash: version.version_hash,
      ...data,
    });
    setOpen(false);
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
          immutableTags={isLatestVersion ? [LATEST_VERSION_TAG] : []}
        />

        <DialogFooter className="gap-3 border-t pt-6 md:gap-0">
          <Button type="button" variant="outline" onClick={handleCancel}>
            Cancel
          </Button>
          <Button type="submit" form={EDIT_VERSION_FORM_ID}>
            Update version
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
};

export default EditVersionDialog;
