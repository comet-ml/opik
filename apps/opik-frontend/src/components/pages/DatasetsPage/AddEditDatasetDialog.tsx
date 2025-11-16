import React, { useCallback, useEffect, useState } from "react";
import { SquareArrowOutUpRight } from "lucide-react";

import useAppStore from "@/store/AppStore";
import useDatasetCreateMutation from "@/api/datasets/useDatasetCreateMutation";
import useDatasetItemBatchMutation from "@/api/datasets/useDatasetItemBatchMutation";
import useDatasetItemsFromCsvMutation from "@/api/datasets/useDatasetItemsFromCsvMutation";
import useDatasetUpdateMutation from "@/api/datasets/useDatasetUpdateMutation";
import { Button } from "@/components/ui/button";
import { Description } from "@/components/ui/description";
import {
  Dialog,
  DialogAutoScrollBody,
  DialogClose,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Textarea } from "@/components/ui/textarea";
import { Card } from "@/components/ui/card";
import { useToast } from "@/components/ui/use-toast";
import ConfirmDialog from "@/components/shared/ConfirmDialog/ConfirmDialog";
import ExplainerDescription from "@/components/shared/ExplainerDescription/ExplainerDescription";
import UploadField from "@/components/shared/UploadField/UploadField";
import Loader from "@/components/shared/Loader/Loader";
import { EXPLAINER_ID, EXPLAINERS_MAP } from "@/constants/explainers";
import { buildDocsUrl } from "@/lib/utils";
import { Dataset } from "@/types/datasets";

const ACCEPTED_TYPE = ".csv";
const FILE_SIZE_LIMIT_IN_MB = 2000;

type AddEditDatasetDialogProps = {
  dataset?: Dataset;
  open: boolean;
  setOpen: (open: boolean) => void;
  onDatasetCreated?: (dataset: Dataset) => void;
  hideUpload?: boolean;
  csvRequired?: boolean;
};

const AddEditDatasetDialog: React.FunctionComponent<
  AddEditDatasetDialogProps
> = ({
  dataset,
  open,
  setOpen,
  onDatasetCreated,
  hideUpload,
  csvRequired = false,
}) => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const { toast } = useToast();

  const { mutate: createMutate } = useDatasetCreateMutation();
  const { mutate: updateMutate } = useDatasetUpdateMutation();
  const { mutate: createItemsMutate } = useDatasetItemBatchMutation();
  const { mutate: createItemsFromCsvMutate } = useDatasetItemsFromCsvMutation();

  const [isOverlayShown, setIsOverlayShown] = useState<boolean>(false);
  const [confirmOpen, setConfirmOpen] = useState<boolean>(false);
  const [csvFile, setCsvFile] = useState<File | undefined>(undefined);
  const [csvError, setCsvError] = useState<string | undefined>(undefined);

  const [name, setName] = useState<string>(dataset ? dataset.name : "");
  const [description, setDescription] = useState<string>(
    dataset ? dataset.description || "" : "",
  );

  // Reset state when dialog closes or when dataset prop changes
  useEffect(() => {
    if (!open) {
      // Reset all state when dialog closes
      setIsOverlayShown(false);
      setCsvFile(undefined);
      setCsvError(undefined);
      setConfirmOpen(false);
      if (!dataset) {
        setName("");
        setDescription("");
      }
    } else {
      // Reset state when dialog opens (in case of stale state)
      setIsOverlayShown(false);
      setConfirmOpen(false);
      if (dataset) {
        setName(dataset.name);
        setDescription(dataset.description || "");
      }
    }
  }, [open, dataset]);

  const isEdit = Boolean(dataset);
  const hasValidCsvFile = csvFile && !csvError;
  // Validation: name is required, and CSV is required only if csvRequired is true
  const isValid =
    Boolean(name.length) &&
    (isEdit || hideUpload || !csvRequired || hasValidCsvFile);
  const title = isEdit ? "Edit dataset" : "Create a new dataset";
  const buttonText = isEdit ? "Update dataset" : "Create dataset";

  const onCreateSuccessHandler = useCallback(
    (newDataset: Dataset) => {
      if (hasValidCsvFile && newDataset.id && csvFile) {
        // Upload CSV file directly to backend
        createItemsFromCsvMutate(
          {
            datasetId: newDataset.id,
            csvFile,
          },
          {
            onSuccess: () => {
              toast({
                title: "CSV upload accepted",
                description: "Your CSV file is being processed in the background. Dataset items will appear shortly.",
              });
            },
            onError: (error: unknown) => {
              console.error("Error uploading CSV file:", error);
              const errorMessage =
                (
                  error as { response?: { data?: { errors?: string[] } } }
                ).response?.data?.errors?.join(", ") ||
                (error as { message?: string }).message ||
                "Failed to upload CSV file";
              toast({
                title: "Error uploading CSV file",
                description: errorMessage,
                variant: "destructive",
              });
            },
            onSettled: () => {
              setOpen(false);
              if (onDatasetCreated) {
                onDatasetCreated(newDataset);
              }
            },
          },
        );
      } else if (onDatasetCreated) {
        onDatasetCreated(newDataset);
      }
    },
    [
      createItemsFromCsvMutate,
      csvFile,
      hasValidCsvFile,
      onDatasetCreated,
      setOpen,
      toast,
    ],
  );

  const submitHandler = useCallback(() => {
    if (isEdit) {
      updateMutate({
        dataset: {
          id: dataset!.id,
          name,
          ...(description && { description }),
        },
      });
    } else {
      createMutate(
        {
          dataset: {
            name,
            ...(description && { description }),
          },
        },
        {
          onSuccess: onCreateSuccessHandler,
          onError: () => setOpen(false),
        },
      );
    }
    if (hasValidCsvFile) {
      setIsOverlayShown(true);
    } else {
      setOpen(false);
    }
  }, [
    isEdit,
    hasValidCsvFile,
    updateMutate,
    dataset,
    name,
    description,
    createMutate,
    onCreateSuccessHandler,
    setOpen,
  ]);

  const handleFileSelect = useCallback((file?: File) => {
    setCsvError(undefined);
    setCsvFile(undefined);

    if (!file) {
      return;
    }

    // Quick size check
    if (file.size > FILE_SIZE_LIMIT_IN_MB * 1024 * 1024) {
      setCsvError(`File exceeds maximum size (${FILE_SIZE_LIMIT_IN_MB}MB).`);
      return;
    }

    // Quick format check
    if (!file.name.toLowerCase().endsWith(".csv")) {
      setCsvError("File must be in .csv format");
      return;
    }

    setCsvFile(file);
  }, []);

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogContent className="max-w-lg sm:max-w-[560px]">
        <DialogHeader>
          <DialogTitle>{title}</DialogTitle>
        </DialogHeader>
        <DialogAutoScrollBody>
          {isOverlayShown && (
            <div className="absolute inset-0 z-10 flex items-center justify-center bg-black/10">
              <Card className="w-3/4">
                <Loader
                  className="min-h-56"
                  message={
                    <div>
                      <div className="comet-body-s-accented text-center">
                        Processing the CSV
                      </div>
                      <div className="comet-body-s mt-2 text-center text-light-slate">
                        This should take less than a minute. <br /> You can
                        safely close this popup while we work.
                      </div>
                      <div className="mt-4 flex items-center justify-center">
                        <Button onClick={() => setOpen(false)}>Close</Button>
                      </div>
                    </div>
                  }
                />
              </Card>
            </div>
          )}
          {!isEdit && (
            <ExplainerDescription
              className="mb-4"
              {...EXPLAINERS_MAP[EXPLAINER_ID.why_do_i_need_multiple_datasets]}
            />
          )}
          <div className="flex flex-col gap-2 pb-4">
            <Label htmlFor="datasetName">Name</Label>
            <Input
              id="datasetName"
              placeholder="Dataset name"
              disabled={isEdit}
              value={name}
              onChange={(event) => setName(event.target.value)}
            />
          </div>
          <div className="flex flex-col gap-2 pb-4">
            <Label htmlFor="datasetDescription">Description</Label>
            <Textarea
              id="datasetDescription"
              placeholder="Dataset description"
              className="min-h-20"
              value={description}
              onChange={(event) => setDescription(event.target.value)}
              maxLength={255}
            />
          </div>
          {!isEdit && !hideUpload && (
            <div className="flex flex-col gap-2 pb-4">
              <Label>Upload a CSV</Label>
              <Description className="tracking-normal">
                Your CSV file can contain up to 1,000 rows, for larger datasets
                use the SDK instead.
                <Button variant="link" size="sm" className="h-5 px-1" asChild>
                  <a
                    href={buildDocsUrl("/evaluation/manage_datasets")}
                    target="_blank"
                    rel="noopener noreferrer"
                  >
                    Learn more
                    <SquareArrowOutUpRight className="ml-0.5 size-3 shrink-0" />
                  </a>
                </Button>
              </Description>
              <UploadField
                disabled={isEdit}
                description="Drop a CSV file to upload or"
                accept={ACCEPTED_TYPE}
                onFileSelect={handleFileSelect}
                errorText={csvError}
                successText={
                  csvFile && !csvError ? "CSV file ready to upload" : undefined
                }
              />
            </div>
          )}
        </DialogAutoScrollBody>
        <DialogFooter>
          <DialogClose asChild>
            <Button variant="outline">
              {isOverlayShown ? "Close" : "Cancel"}
            </Button>
          </DialogClose>
          <Button
            type="submit"
            disabled={!isValid}
            onClick={csvError ? () => setConfirmOpen(true) : submitHandler}
          >
            {buttonText}
          </Button>
        </DialogFooter>
      </DialogContent>
      <ConfirmDialog
        open={confirmOpen}
        setOpen={setConfirmOpen}
        onCancel={submitHandler}
        title="File can’t be uploaded"
        description="This file cannot be uploaded because it does not pass validation. If you continue, the dataset will be created without any items. You can add items manually later, or go back and upload a valid file."
        cancelText="Create empty dataset"
        confirmText="Go back"
      />
    </Dialog>
  );
};

export default AddEditDatasetDialog;
