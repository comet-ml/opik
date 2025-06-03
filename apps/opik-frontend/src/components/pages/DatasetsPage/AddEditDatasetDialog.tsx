import React, { useCallback, useState } from "react";
import { csv2json } from "json-2-csv";
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
import { useToast } from "@/components/ui/use-toast";
import ConfirmDialog from "@/components/shared/ConfirmDialog/ConfirmDialog";
import ExplainerDescription from "@/components/shared/ExplainerDescription/ExplainerDescription";
import UploadField from "@/components/shared/UploadField/UploadField";
import { EXPLAINER_ID, EXPLAINERS_MAP } from "@/constants/explainers";
import { buildDocsUrl } from "@/lib/utils";
import { Dataset, DATASET_ITEM_SOURCE } from "@/types/datasets";

const ACCEPTED_TYPE = ".csv";

type AddEditDatasetDialogProps = {
  dataset?: Dataset;
  open: boolean;
  setOpen: (open: boolean) => void;
  onDatasetCreated?: (dataset: Dataset) => void;
};

const AddEditDatasetDialog: React.FunctionComponent<
  AddEditDatasetDialogProps
> = ({ dataset, open, setOpen, onDatasetCreated }) => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const { toast } = useToast();

  const { mutate: createMutate } = useDatasetCreateMutation();
  const { mutate: updateMutate } = useDatasetUpdateMutation();
  const { mutate: createItemsMutate } = useDatasetItemBatchMutation();

  const [confirmOpen, setConfirmOpen] = useState<boolean>(false);
  const [csvData, setCsvData] = useState<Record<string, unknown>[] | undefined>(
    undefined,
  );
  const [csvError, setCsvError] = useState<string | undefined>(undefined);

  const [name, setName] = useState<string>(dataset ? dataset.name : "");
  const [description, setDescription] = useState<string>(
    dataset ? dataset.description || "" : "",
  );

  const isEdit = Boolean(dataset);
  const isValid = Boolean(name.length);
  const title = isEdit ? "Edit dataset" : "Create a new dataset";
  const buttonText = isEdit ? "Update dataset" : "Create dataset";

  const onCreateSuccessHandler = useCallback(
    (newDataset: Dataset) => {
      if (csvData && csvData.length > 0) {
        // Prepare items with manual source and data fields
        const headers = Object.keys(csvData[0]);
        const [inputKey, outputKey] = headers;
        const items = csvData.map((row) => ({
          source: DATASET_ITEM_SOURCE.manual,
          data: {
            [inputKey]: row[inputKey],
            [outputKey]: row[outputKey],
          },
        }));

        createItemsMutate(
          {
            datasetName: newDataset.name,
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
    [createItemsMutate, csvData, onDatasetCreated, toast, workspaceName],
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
        },
      );
    }
    setOpen(false);
  }, [
    isEdit,
    setOpen,
    updateMutate,
    dataset,
    name,
    description,
    createMutate,
    onCreateSuccessHandler,
  ]);

  const handleFileSelect = useCallback(async (file?: File) => {
    setCsvError(undefined);
    setCsvData(undefined);
    if (!file) return;

    try {
      // Validate file size (50MB = 50 * 1024 * 1024 bytes)
      if (file.size > 50 * 1024 * 1024) {
        setCsvError("File exceeds maximum size (50MB).");
        return;
      }

      // Validate mime type
      if (!file.type || !file.type.includes("text/csv")) {
        setCsvError("File must be in .csv format");
        return;
      }

      const text = await file.text();

      const parsed = await csv2json(text, {
        excelBOM: true,
        trimHeaderFields: true,
        trimFieldValues: true,
      });

      if (!Array.isArray(parsed)) {
        setCsvError("Invalid CSV format.");
        return;
      }

      if (parsed.length === 0) {
        setCsvError("CSV file is empty.");
        return;
      }

      if (parsed.length > 1000) {
        setCsvError("File is too large (max. 1,000 rows)");
        return;
      }

      const headers = Object.keys(parsed[0] as object);
      if (
        headers.length !== 2 ||
        !headers.includes("input") ||
        !headers.includes("output")
      ) {
        setCsvError(
          "File must have only two columns named 'input' and 'output'.",
        );
        return;
      }

      setCsvData(parsed as Record<string, unknown>[]);
    } catch (err) {
      setCsvError("Failed to process CSV file.");
      console.error(err);
      return;
    }
  }, []);

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogContent className="max-w-lg sm:max-w-[560px]">
        <DialogHeader>
          <DialogTitle>{title}</DialogTitle>
        </DialogHeader>
        <DialogAutoScrollBody>
          {!isEdit && (
            <ExplainerDescription
              className="mb-4"
              size="sm"
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
          {!isEdit && (
            <div className="flex flex-col gap-2 pb-4">
              <Label>Upload a CSV (optional)</Label>
              <Description className="tracking-normal">
                Your CSV file should contain only two columns (input and output)
                and up to 1,000 rows. For larger datasets, use the SDK instead.
                <Button variant="link" size="sm" className="px-0" asChild>
                  <a
                    href={buildDocsUrl("/evaluation/manage_datasets")}
                    target="_blank"
                    rel="noopener noreferrer"
                  >
                    Learn more
                    <SquareArrowOutUpRight className="ml-0.5 size-3 shrink-0" />
                  </a>
                </Button>
                <br />
                You can also skip this step and add dataset items manually
                later.
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
            <Button variant="outline">Cancel</Button>
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
