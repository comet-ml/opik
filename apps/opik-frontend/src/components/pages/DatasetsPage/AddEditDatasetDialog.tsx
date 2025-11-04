import React, { useCallback, useEffect, useState } from "react";
import { SquareArrowOutUpRight } from "lucide-react";

import useAppStore from "@/store/AppStore";
import useDatasetCreateMutation from "@/api/datasets/useDatasetCreateMutation";
import useDatasetItemBatchMutation from "@/api/datasets/useDatasetItemBatchMutation";
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
import { validateCsvFile } from "@/lib/file";
import { Dataset, DATASET_ITEM_SOURCE } from "@/types/datasets";

const ACCEPTED_TYPE = ".csv";
const FILE_SIZE_LIMIT_IN_MB = 20;
const MAX_ITEMS_COUNT_LIMIT = 1000;

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

  const [isOverlayShown, setIsOverlayShown] = useState<boolean>(false);
  const [confirmOpen, setConfirmOpen] = useState<boolean>(false);
  const [csvData, setCsvData] = useState<Record<string, unknown>[] | undefined>(
    undefined,
  );
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
      setCsvData(undefined);
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
  const hasValidCsvData = csvData && csvData.length > 0;
  // Validation: name is required, and CSV is required only if csvRequired is true
  const isValid =
    Boolean(name.length) &&
    (isEdit || hideUpload || !csvRequired || hasValidCsvData);
  const title = isEdit ? "Edit dataset" : "Create a new dataset";
  const buttonText = isEdit ? "Update dataset" : "Create dataset";

  const onCreateSuccessHandler = useCallback(
    (newDataset: Dataset) => {
      if (hasValidCsvData && newDataset.id) {
        // Prepare items with manual source and data fields
        const headers = Object.keys(csvData[0]);
        const items = csvData.map((row) => ({
          source: DATASET_ITEM_SOURCE.manual,
          data: headers.reduce<Record<string, unknown>>((acc, header) => {
            acc[header] = row[header];
            return acc;
          }, {}),
        }));

        createItemsMutate(
          {
            datasetId: newDataset.id,
            datasetItems: items,
            workspaceName,
          },
          {
            onError: (error) => {
              console.error("Error uploading dataset items:", error);
              toast({
                title: "Error uploading dataset items",
                description:
                  (
                    error as { response?: { data?: { errors?: string[] } } }
                  ).response?.data?.errors?.join(", ") ||
                  error.message ||
                  "Failed to add dataset items",
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
      createItemsMutate,
      csvData,
      hasValidCsvData,
      onDatasetCreated,
      setOpen,
      toast,
      workspaceName,
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
    if (hasValidCsvData) {
      setIsOverlayShown(true);
    } else {
      setOpen(false);
    }
  }, [
    isEdit,
    hasValidCsvData,
    updateMutate,
    dataset,
    name,
    description,
    createMutate,
    onCreateSuccessHandler,
    setOpen,
  ]);

  const handleFileSelect = useCallback(async (file?: File) => {
    setCsvError(undefined);
    setCsvData(undefined);

    const { data, error } = await validateCsvFile(
      file,
      FILE_SIZE_LIMIT_IN_MB,
      MAX_ITEMS_COUNT_LIMIT,
    );

    if (error) {
      setCsvError(error);
      return;
    }

    if (data) {
      setCsvData(data);
    }
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
                  csvData && !csvError ? "Valid CSV format" : undefined
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
