import React, { useCallback, useEffect, useState } from "react";
import { SquareArrowOutUpRight } from "lucide-react";
import { AxiosError, HttpStatusCode } from "axios";
import get from "lodash/get";

import useAppStore from "@/store/AppStore";
import useDatasetCreateMutation from "@/api/datasets/useDatasetCreateMutation";
import useDatasetItemBatchMutation from "@/api/datasets/useDatasetItemBatchMutation";
import useDatasetItemsFromCsvMutation from "@/api/datasets/useDatasetItemsFromCsvMutation";
import useDatasetUpdateMutation from "@/api/datasets/useDatasetUpdateMutation";
import { useFetchDataset } from "@/api/datasets/useDatasetById";
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
import { validateCsvFile, getCsvFilenameWithoutExtension } from "@/lib/file";
import { Dataset, DATASET_ITEM_SOURCE } from "@/types/datasets";
import { FeatureToggleKeys } from "@/types/feature-toggles";
import { useIsFeatureEnabled } from "@/components/feature-toggles-provider";

const ACCEPTED_TYPE = ".csv";

// JSON mode (toggle OFF) - with restrictions
const JSON_MODE_FILE_SIZE_LIMIT_IN_MB = 20;
const JSON_MODE_MAX_ITEMS = 1000;

// CSV mode (toggle ON) - no restrictions
const CSV_MODE_FILE_SIZE_LIMIT_IN_MB = 2000;

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
  const isCsvUploadEnabled = useIsFeatureEnabled(
    FeatureToggleKeys.CSV_UPLOAD_ENABLED,
  );

  const { mutate: createMutate } = useDatasetCreateMutation();
  const { mutate: updateMutate } = useDatasetUpdateMutation();
  const { mutate: createItemsMutate } = useDatasetItemBatchMutation();
  const { mutate: createItemsFromCsvMutate } = useDatasetItemsFromCsvMutation();
  const fetchDataset = useFetchDataset();

  const [isOverlayShown, setIsOverlayShown] = useState<boolean>(false);
  const [confirmOpen, setConfirmOpen] = useState<boolean>(false);
  const [csvFile, setCsvFile] = useState<File | undefined>(undefined);
  const [csvData, setCsvData] = useState<Record<string, unknown>[] | undefined>(
    undefined,
  );
  const [csvError, setCsvError] = useState<string | undefined>(undefined);

  const [name, setName] = useState<string>(dataset ? dataset.name : "");
  const [nameError, setNameError] = useState<string | undefined>(undefined);
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
      setCsvData(undefined);
      setConfirmOpen(false);
      setNameError(undefined);
      if (!dataset) {
        setName("");
        setDescription("");
      }
    } else {
      // Reset state when dialog opens (in case of stale state)
      setIsOverlayShown(false);
      setConfirmOpen(false);
      setNameError(undefined);
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

  // Determine which mode we're in and set limits accordingly
  const fileSizeLimit = isCsvUploadEnabled
    ? CSV_MODE_FILE_SIZE_LIMIT_IN_MB
    : JSON_MODE_FILE_SIZE_LIMIT_IN_MB;

  const onCreateSuccessHandler = useCallback(
    (newDataset: Dataset) => {
      if (hasValidCsvFile) {
        setIsOverlayShown(true);
      }

      if (hasValidCsvFile && newDataset.id) {
        if (isCsvUploadEnabled && csvFile) {
          // CSV mode: Upload CSV file directly to backend
          createItemsFromCsvMutate(
            {
              datasetId: newDataset.id,
              csvFile,
            },
            {
              onSuccess: () => {
                toast({
                  title: "CSV upload accepted",
                  description:
                    "Your CSV file is being processed in the background. Dataset items will appear automatically when ready. If you don't see them, try refreshing the page.",
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
        } else if (!isCsvUploadEnabled && csvData) {
          // JSON mode: Send parsed JSON data
          createItemsMutate(
            {
              datasetId: newDataset.id,
              workspaceName,
              datasetItems: csvData.map((row) => ({
                data: row,
                source: DATASET_ITEM_SOURCE.manual,
              })),
            },
            {
              onSuccess: () => {
                // Fetch dataset to get latest_version populated by backend
                fetchDataset({ datasetId: newDataset.id })
                  .then((enrichedDataset) => {
                    if (onDatasetCreated) {
                      onDatasetCreated(enrichedDataset);
                    }
                  })
                  .catch((error) => {
                    console.error(
                      "Failed to fetch dataset after item creation:",
                      error,
                    );

                    if (onDatasetCreated) {
                      onDatasetCreated(newDataset);
                    }
                  });
              },
              onError: () => {
                setOpen(false);
              },
            },
          );
        }
      } else {
        setOpen(false);
        if (onDatasetCreated) {
          onDatasetCreated(newDataset);
        }
      }
    },
    [
      hasValidCsvFile,
      isCsvUploadEnabled,
      csvFile,
      csvData,
      createItemsFromCsvMutate,
      createItemsMutate,
      workspaceName,
      onDatasetCreated,
      setOpen,
      toast,
      fetchDataset,
    ],
  );

  const handleMutationError = useCallback(
    (error: AxiosError, action: "create" | "update") => {
      const statusCode = get(error, ["response", "status"]);
      const errorMessage =
        get(error, ["response", "data", "message"]) ||
        get(error, ["response", "data", "errors", 0]) ||
        get(error, ["message"]);

      if (statusCode === HttpStatusCode.Conflict) {
        setNameError("This name already exists");
      } else {
        toast({
          title: `Error saving dataset`,
          description: errorMessage || `Failed to ${action} dataset`,
          variant: "destructive",
        });
        setOpen(false);
      }
    },
    [toast, setOpen],
  );

  const submitHandler = useCallback(() => {
    if (isEdit) {
      updateMutate(
        {
          dataset: {
            id: dataset!.id,
            name,
            ...(description && { description }),
          },
        },
        {
          onSuccess: () => {
            setOpen(false);
          },
          onError: (error: AxiosError) => handleMutationError(error, "update"),
        },
      );
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
          onError: (error: AxiosError) => handleMutationError(error, "create"),
        },
      );
    }
  }, [
    isEdit,
    updateMutate,
    dataset,
    name,
    description,
    createMutate,
    onCreateSuccessHandler,
    setOpen,
    handleMutationError,
  ]);

  const handleFileSelect = useCallback(
    async (file?: File) => {
      setCsvError(undefined);
      setCsvFile(undefined);
      setCsvData(undefined);

      if (!file) {
        return;
      }

      if (isCsvUploadEnabled) {
        // CSV mode: Just validate size and format, don't parse
        if (file.size > fileSizeLimit * 1024 * 1024) {
          setCsvError(`File exceeds maximum size (${fileSizeLimit}MB).`);
          return;
        }

        if (!file.name.toLowerCase().endsWith(".csv")) {
          setCsvError("File must be in .csv format");
          return;
        }

        setCsvFile(file);

        // Autofill dataset name from filename if name is empty
        if (!name.trim()) {
          setName(getCsvFilenameWithoutExtension(file.name));
        }
      } else {
        // JSON mode: Validate and parse CSV with row limit
        const result = await validateCsvFile(
          file,
          fileSizeLimit,
          JSON_MODE_MAX_ITEMS,
        );

        if (result.error) {
          setCsvError(result.error);
        } else if (result.data) {
          setCsvFile(file);
          setCsvData(result.data);

          // Autofill dataset name from filename if name is empty
          if (!name.trim()) {
            setName(getCsvFilenameWithoutExtension(file.name));
          }
        }
      }
    },
    [isCsvUploadEnabled, fileSizeLimit, name],
  );

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
                        {isCsvUploadEnabled
                          ? "Upload in progress..."
                          : "Processing the CSV"}
                      </div>
                      {!isCsvUploadEnabled && (
                        <>
                          <div className="comet-body-s mt-2 text-center text-light-slate">
                            This should take less than a minute. <br /> You can
                            safely close this popup while we work.
                          </div>
                          <div className="mt-4 flex items-center justify-center">
                            <Button onClick={() => setOpen(false)}>
                              Close
                            </Button>
                          </div>
                        </>
                      )}
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
              value={name}
              className={
                nameError &&
                "!border-destructive focus-visible:!border-destructive"
              }
              onChange={(event) => {
                setName(event.target.value);
                setNameError(undefined);
              }}
              onKeyDown={(event) => {
                if (event.key === "Enter" && isValid) {
                  event.preventDefault();
                  csvError ? setConfirmOpen(true) : submitHandler();
                }
              }}
            />
            <span
              className={`comet-body-xs min-h-4 ${
                nameError ? "text-destructive" : "invisible"
              }`}
            >
              {nameError || " "}
            </span>
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
                {isCsvUploadEnabled ? (
                  <>
                    Your CSV file can be up to {fileSizeLimit}MB in size. The
                    file will be processed in the background.
                  </>
                ) : (
                  <>
                    Your CSV file can contain up to 1,000 rows, for larger
                    datasets use the SDK instead.
                  </>
                )}
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
