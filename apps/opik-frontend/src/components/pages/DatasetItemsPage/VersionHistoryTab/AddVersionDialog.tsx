import React, { useCallback } from "react";
import { Blocks, Code2, Loader2 } from "lucide-react";

import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { useToast } from "@/components/ui/use-toast";
import { ToastAction } from "@/components/ui/toast";
import { LATEST_VERSION_TAG } from "@/constants/datasets";
import useCommitDatasetVersionMutation from "@/api/datasets/useCommitDatasetVersionMutation";
import { useNavigateToExperiment } from "@/hooks/useNavigateToExperiment";
import useLoadPlayground from "@/hooks/useLoadPlayground";
import VersionForm, { VersionFormData } from "./VersionForm";

const ADD_VERSION_FORM_ID = "add-version-form";

type AddVersionDialogProps = {
  open: boolean;
  setOpen: (open: boolean) => void;
  datasetId: string;
  datasetName?: string;
  onConfirm?: (tags?: string[], changeDescription?: string) => void;
};

const AddVersionDialog: React.FC<AddVersionDialogProps> = ({
  open,
  setOpen,
  datasetId,
  datasetName,
  onConfirm,
}) => {
  const commitVersionMutation = useCommitDatasetVersionMutation();
  const { toast } = useToast();
  const { navigate: navigateToExperiment } = useNavigateToExperiment();
  const { loadPlayground } = useLoadPlayground();

  const showSuccessToast = useCallback(
    (versionId?: string) => {
      toast({
        title: "New version created",
        description:
          "Your dataset changes have been saved as a new version. You can now use it to run experiments in the SDK or the Playground.",
        actions: [
          <ToastAction
            variant="link"
            size="sm"
            className="comet-body-s-accented gap-1.5 px-0"
            altText="Run experiment in the SDK"
            key="sdk"
            onClick={() =>
              navigateToExperiment({
                newExperiment: true,
                datasetName,
              })
            }
          >
            <Code2 className="size-4" />
            Run experiment in the SDK
          </ToastAction>,
          <ToastAction
            variant="link"
            size="sm"
            className="comet-body-s-accented gap-1.5 px-0"
            altText="Run experiment in the Playground"
            key="playground"
            onClick={() =>
              loadPlayground({
                datasetId,
                datasetVersionId: versionId,
              })
            }
          >
            <Blocks className="size-4" />
            Run experiment in the Playground
          </ToastAction>,
        ],
      });
    },
    [toast, navigateToExperiment, datasetName, loadPlayground, datasetId],
  );

  const handleSubmit = (data: VersionFormData) => {
    // If onConfirm is provided, use it (draft mode)
    if (onConfirm) {
      onConfirm(data.tags, data.versionNote);
      return;
    }

    // Otherwise, use the legacy commit mutation
    commitVersionMutation.mutate(
      {
        datasetId,
        changeDescription: data.versionNote,
        tags: data.tags,
      },
      {
        onSuccess: (version) => {
          showSuccessToast(version.id);
          setOpen(false);
        },
      },
    );
  };

  const handleCancel = () => {
    setOpen(false);
  };

  const handleOpenChange = (newOpen: boolean) => {
    if (commitVersionMutation.isPending) return;
    setOpen(newOpen);
  };

  const isSubmitting = commitVersionMutation.isPending;

  return (
    <Dialog open={open} onOpenChange={handleOpenChange}>
      <DialogContent className="max-w-lg">
        <DialogHeader>
          <DialogTitle>Save changes</DialogTitle>
        </DialogHeader>

        <p className="text-sm text-muted-foreground">
          Saving your changes will create a new version. You&apos;ll be able to
          use it in experiments or in the Playground. The previous version will
          remain available in version history.
        </p>

        <VersionForm
          id={ADD_VERSION_FORM_ID}
          onSubmit={handleSubmit}
          immutableTags={[LATEST_VERSION_TAG]}
        />

        <DialogFooter className="gap-3 border-t pt-6 md:gap-0">
          <Button
            type="button"
            variant="outline"
            onClick={handleCancel}
            disabled={isSubmitting}
          >
            Cancel
          </Button>
          <Button
            type="submit"
            form={ADD_VERSION_FORM_ID}
            disabled={isSubmitting}
          >
            {isSubmitting && <Loader2 className="mr-2 size-4 animate-spin" />}
            Save changes
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
};

export default AddVersionDialog;
