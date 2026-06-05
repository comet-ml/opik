import { useCallback, useEffect, useState } from "react";
import { AxiosError, HttpStatusCode } from "axios";
import get from "lodash/get";

import useDatasetCreateMutation from "@/api/datasets/useDatasetCreateMutation";
import useDatasetItemsFromCsvMutation from "@/api/datasets/useDatasetItemsFromCsvMutation";
import useDatasetItemsFromJsonMutation from "@/api/datasets/useDatasetItemsFromJsonMutation";
import useDatasetUpdateMutation from "@/api/datasets/useDatasetUpdateMutation";
import { Button } from "@/ui/button";
import {
  Dialog,
  DialogAutoScrollBody,
  DialogClose,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/ui/dialog";
import { Input } from "@/ui/input";
import { Label } from "@/ui/label";
import { Textarea } from "@/ui/textarea";
import { useToast } from "@/ui/use-toast";
import ConfirmDialog from "@/shared/ConfirmDialog/ConfirmDialog";
import DatasetUploadDescription from "@/v1/pages-shared/datasets/DatasetUploadDescription";
import DatasetUploadField from "@/v1/pages-shared/datasets/DatasetUploadField";
import { buildDocsUrl } from "@/v1/lib/utils";
import { getApiErrorMessage } from "@/lib/api-error";
import {
  formatToHumanLabel,
  getDatasetUploadFilenameWithoutExtension,
  UploadFormat,
  validateDatasetUploadFile,
} from "@/lib/file";
import { Dataset, DATASET_TYPE } from "@/types/datasets";

const FILE_SIZE_LIMIT_IN_MB = 2000;

type AddEditTestSuiteDialogProps = {
  dataset?: Dataset;
  open: boolean;
  setOpen: (open: boolean) => void;
  onDatasetCreated?: (dataset: Dataset) => void;
  hideUpload?: boolean;
  csvRequired?: boolean;
};

const AddEditTestSuiteDialog = ({
  dataset,
  open,
  setOpen,
  onDatasetCreated,
  hideUpload,
  csvRequired = false,
}: AddEditTestSuiteDialogProps) => {
  const { toast } = useToast();

  const { mutate: createMutate } = useDatasetCreateMutation();
  const { mutate: updateMutate } = useDatasetUpdateMutation();
  const { mutate: createItemsFromCsvMutate } = useDatasetItemsFromCsvMutation();
  const { mutate: createItemsFromJsonMutate } =
    useDatasetItemsFromJsonMutation();

  const [confirmOpen, setConfirmOpen] = useState<boolean>(false);
  const [uploadFile, setUploadFile] = useState<File | undefined>(undefined);
  const [uploadError, setUploadError] = useState<string | undefined>(undefined);
  const [uploadFormat, setUploadFormat] = useState<UploadFormat | undefined>(
    undefined,
  );

  const [type, setType] = useState<DATASET_TYPE>(DATASET_TYPE.DATASET);
  const [name, setName] = useState<string>(dataset ? dataset.name : "");
  const [nameError, setNameError] = useState<string | undefined>(undefined);
  const [description, setDescription] = useState<string>(
    dataset ? dataset.description || "" : "",
  );

  useEffect(() => {
    setConfirmOpen(false);
    setNameError(undefined);

    if (!open) {
      setUploadFile(undefined);
      setUploadError(undefined);
      setUploadFormat(undefined);
      setType(DATASET_TYPE.DATASET);
      if (!dataset) {
        setName("");
        setDescription("");
      }
    } else if (dataset) {
      setName(dataset.name);
      setDescription(dataset.description || "");
    }
  }, [open, dataset]);

  const isEdit = Boolean(dataset);
  const hasValidUploadFile = uploadFile && !uploadError;
  // Validation: name is required, and CSV is required only if csvRequired is true
  const isValid =
    name.length > 0 &&
    (isEdit || hideUpload || !csvRequired || hasValidUploadFile);

  const typeLabel = type === DATASET_TYPE.TEST_SUITE ? "test suite" : "dataset";
  const title = isEdit ? "Edit" : "Create new";
  const buttonText = isEdit ? "Update" : "Create new";

  const fileSizeLimit = FILE_SIZE_LIMIT_IN_MB;

  const onCreateSuccessHandler = useCallback(
    (newDataset: Dataset) => {
      if (hasValidUploadFile && uploadFile && uploadFormat && newDataset.id) {
        const label = formatToHumanLabel(uploadFormat);
        const handlers = {
          onSuccess: () => {
            toast({
              title: `${label} upload accepted`,
              description: `Your ${label} file is being processed in the background. Items will appear automatically when ready. If you don't see them, try refreshing the page.`,
            });
          },
          onError: (error: unknown) => {
            console.error(`Error uploading ${label} file:`, error);
            toast({
              title: `Error uploading ${label} file`,
              description: getApiErrorMessage(
                error,
                `Failed to upload ${label} file`,
              ),
              variant: "destructive",
            });
          },
          onSettled: () => {
            setOpen(false);
            if (onDatasetCreated) {
              onDatasetCreated(newDataset);
            }
          },
        };

        if (uploadFormat === "csv") {
          createItemsFromCsvMutate(
            { datasetId: newDataset.id, csvFile: uploadFile },
            handlers,
          );
        } else {
          createItemsFromJsonMutate(
            {
              datasetId: newDataset.id,
              jsonFile: uploadFile,
              format: uploadFormat,
            },
            handlers,
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
      hasValidUploadFile,
      uploadFile,
      uploadFormat,
      createItemsFromCsvMutate,
      createItemsFromJsonMutate,
      onDatasetCreated,
      setOpen,
      toast,
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
          title: "Error saving",
          description: errorMessage || `Failed to ${action}`,
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
            type,
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
    type,
    createMutate,
    onCreateSuccessHandler,
    setOpen,
    handleMutationError,
  ]);

  const handleFileSelect = useCallback(
    (file?: File) => {
      setUploadError(undefined);
      setUploadFile(undefined);
      setUploadFormat(undefined);

      if (!file) {
        return;
      }

      const result = validateDatasetUploadFile(file, fileSizeLimit);
      if (result.error) {
        setUploadError(result.error);
        return;
      }
      if (!result.file || !result.format) return;

      setUploadFile(result.file);
      setUploadFormat(result.format);

      if (!name.trim()) {
        setName(getDatasetUploadFilenameWithoutExtension(result.file.name));
      }
    },
    [fileSizeLimit, name],
  );

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogContent className="max-w-lg sm:max-w-[560px]">
        <DialogHeader>
          <DialogTitle>{title}</DialogTitle>
        </DialogHeader>
        <DialogAutoScrollBody>
          <div className="flex flex-col gap-2 pb-4">
            <Label htmlFor="testSuiteName">Name</Label>
            <Input
              id="testSuiteName"
              placeholder="Name"
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
                  uploadError ? setConfirmOpen(true) : submitHandler();
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
            <Label htmlFor="testSuiteDescription">Description</Label>
            <Textarea
              id="testSuiteDescription"
              placeholder="Description"
              className="min-h-20"
              value={description}
              onChange={(event) => setDescription(event.target.value)}
              maxLength={255}
            />
          </div>
          {!isEdit && !hideUpload && (
            <div className="flex flex-col gap-2 pb-4">
              <Label>Upload a CSV or JSON file</Label>
              <DatasetUploadDescription
                fileSizeLimit={fileSizeLimit}
                docsUrl={buildDocsUrl("/evaluation/manage_datasets")}
              />
              <DatasetUploadField
                uploadFile={uploadFile}
                uploadFormat={uploadFormat}
                uploadError={uploadError}
                onFileSelect={handleFileSelect}
                disabled={isEdit}
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
            onClick={uploadError ? () => setConfirmOpen(true) : submitHandler}
          >
            {buttonText}
          </Button>
        </DialogFooter>
      </DialogContent>
      <ConfirmDialog
        open={confirmOpen}
        setOpen={setConfirmOpen}
        onCancel={submitHandler}
        title="File can't be uploaded"
        description={`This file cannot be uploaded because it does not pass validation. If you continue, the ${typeLabel} will be created without any items. You can add items manually later, or go back and upload a valid file.`}
        cancelText={`Create empty ${typeLabel}`}
        confirmText="Go back"
      />
    </Dialog>
  );
};

export default AddEditTestSuiteDialog;
