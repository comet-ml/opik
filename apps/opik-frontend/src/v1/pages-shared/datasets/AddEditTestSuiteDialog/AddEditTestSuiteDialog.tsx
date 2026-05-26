import { useCallback, useEffect, useState } from "react";
import { ExternalLink } from "lucide-react";
import { AxiosError, HttpStatusCode } from "axios";
import get from "lodash/get";

import useDatasetCreateMutation from "@/api/datasets/useDatasetCreateMutation";
import useDatasetItemsFromCsvMutation from "@/api/datasets/useDatasetItemsFromCsvMutation";
import useDatasetUpdateMutation from "@/api/datasets/useDatasetUpdateMutation";
import { Button } from "@/ui/button";
import { Description } from "@/ui/description";
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
import UploadField from "@/shared/UploadField/UploadField";
import { buildDocsUrl } from "@/v1/lib/utils";
import { getCsvFilenameWithoutExtension } from "@/lib/file";
import { Dataset, DATASET_TYPE } from "@/types/datasets";

const ACCEPTED_TYPE = ".csv";

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

  const [confirmOpen, setConfirmOpen] = useState<boolean>(false);
  const [csvFile, setCsvFile] = useState<File | undefined>(undefined);
  const [csvError, setCsvError] = useState<string | undefined>(undefined);

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
      setCsvFile(undefined);
      setCsvError(undefined);
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
  const hasValidCsvFile = csvFile && !csvError;
  // Validation: name is required, and CSV is required only if csvRequired is true
  const isValid =
    name.length > 0 &&
    (isEdit || hideUpload || !csvRequired || hasValidCsvFile);

  const typeLabel = type === DATASET_TYPE.TEST_SUITE ? "test suite" : "dataset";
  const title = isEdit ? "Edit" : "Create new";
  const buttonText = isEdit ? "Update" : "Create new";

  const fileSizeLimit = FILE_SIZE_LIMIT_IN_MB;

  const onCreateSuccessHandler = useCallback(
    (newDataset: Dataset) => {
      if (hasValidCsvFile && csvFile && newDataset.id) {
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
                  "Your CSV file is being processed in the background. Items will appear automatically when ready. If you don't see them, try refreshing the page.",
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
      } else {
        setOpen(false);
        if (onDatasetCreated) {
          onDatasetCreated(newDataset);
        }
      }
    },
    [
      hasValidCsvFile,
      csvFile,
      createItemsFromCsvMutate,
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
      setCsvError(undefined);
      setCsvFile(undefined);

      if (!file) {
        return;
      }

      if (file.size > fileSizeLimit * 1024 * 1024) {
        setCsvError(`File exceeds maximum size (${fileSizeLimit}MB).`);
        return;
      }

      if (!file.name.toLowerCase().endsWith(".csv")) {
        setCsvError("File must be in .csv format");
        return;
      }

      setCsvFile(file);

      if (!name.trim()) {
        setName(getCsvFilenameWithoutExtension(file.name));
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
              <Label>Upload a CSV</Label>
              <Description className="tracking-normal">
                Your CSV file can be up to {fileSizeLimit}MB in size. The file
                will be processed in the background.
                <Button variant="link" size="sm" className="h-5 px-1" asChild>
                  <a
                    href={buildDocsUrl("/evaluation/manage_datasets")}
                    target="_blank"
                    rel="noopener noreferrer"
                  >
                    Learn more
                    <ExternalLink className="ml-0.5 size-3 shrink-0" />
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
        title="File can't be uploaded"
        description={`This file cannot be uploaded because it does not pass validation. If you continue, the ${typeLabel} will be created without any items. You can add items manually later, or go back and upload a valid file.`}
        cancelText={`Create empty ${typeLabel}`}
        confirmText="Go back"
      />
    </Dialog>
  );
};

export default AddEditTestSuiteDialog;
