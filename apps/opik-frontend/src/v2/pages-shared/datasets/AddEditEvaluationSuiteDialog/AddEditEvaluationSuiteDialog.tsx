import { useCallback, useEffect, useState } from "react";
import { ExternalLink } from "lucide-react";
import { AxiosError, HttpStatusCode } from "axios";
import get from "lodash/get";

import useAppStore, { useActiveProjectId } from "@/store/AppStore";
import useDatasetCreateMutation from "@/api/datasets/useDatasetCreateMutation";
import useDatasetItemBatchMutation from "@/api/datasets/useDatasetItemBatchMutation";
import useDatasetItemsFromCsvMutation from "@/api/datasets/useDatasetItemsFromCsvMutation";
import useDatasetUpdateMutation from "@/api/datasets/useDatasetUpdateMutation";
import useDatasetItemChangesMutation from "@/api/datasets/useDatasetItemChangesMutation";
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
import { Separator } from "@/ui/separator";
import { Card } from "@/ui/card";
import { useToast } from "@/ui/use-toast";
import { cn, buildDocsUrl } from "@/lib/utils";
import ConfirmDialog from "@/shared/ConfirmDialog/ConfirmDialog";
import UploadField from "@/shared/UploadField/UploadField";
import AssertionsField from "@/shared/AssertionField/AssertionsField";
import Loader from "@/shared/Loader/Loader";
import { validateCsvFile, getCsvFilenameWithoutExtension } from "@/lib/file";
import { packAssertions } from "@/lib/assertion-converters";
import { Dataset, DATASET_TYPE, DATASET_ITEM_SOURCE } from "@/types/datasets";
import { MAX_RUNS_PER_ITEM } from "@/types/evaluation-suites";
import { FeatureToggleKeys } from "@/types/feature-toggles";
import { useIsFeatureEnabled } from "@/contexts/feature-toggles-provider";
import { useClampedIntegerInput } from "@/hooks/useClampedIntegerInput";

const ACCEPTED_TYPE = ".csv";

// JSON mode (toggle OFF) - with restrictions
const JSON_MODE_FILE_SIZE_LIMIT_IN_MB = 20;
const JSON_MODE_MAX_ITEMS = 1000;

// CSV mode (toggle ON) - no restrictions
const CSV_MODE_FILE_SIZE_LIMIT_IN_MB = 2000;

type AddEditEvaluationSuiteDialogProps = {
  dataset?: Dataset;
  open: boolean;
  setOpen: (open: boolean) => void;
  onDatasetCreated?: (dataset: Dataset) => void;
  hideUpload?: boolean;
  csvRequired?: boolean;
};

const AddEditEvaluationSuiteDialog = ({
  dataset,
  open,
  setOpen,
  onDatasetCreated,
  hideUpload,
  csvRequired = false,
}: AddEditEvaluationSuiteDialogProps) => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const activeProjectId = useActiveProjectId();
  const { toast } = useToast();
  const isCsvUploadEnabled = useIsFeatureEnabled(
    FeatureToggleKeys.CSV_UPLOAD_ENABLED,
  );

  const { mutate: createMutate } = useDatasetCreateMutation();
  const { mutate: updateMutate } = useDatasetUpdateMutation();
  const { mutate: createItemsMutate } = useDatasetItemBatchMutation();
  const { mutate: createItemsFromCsvMutate } = useDatasetItemsFromCsvMutation();
  const { mutate: changesMutate } = useDatasetItemChangesMutation();
  const [isOverlayShown, setIsOverlayShown] = useState<boolean>(false);
  const [confirmOpen, setConfirmOpen] = useState<boolean>(false);
  const [csvFile, setCsvFile] = useState<File | undefined>(undefined);
  const [csvData, setCsvData] = useState<Record<string, unknown>[] | undefined>(
    undefined,
  );
  const [csvError, setCsvError] = useState<string | undefined>(undefined);

  const [type, setType] = useState<DATASET_TYPE>(DATASET_TYPE.EVALUATION_SUITE);
  const [name, setName] = useState<string>(dataset ? dataset.name : "");
  const [nameError, setNameError] = useState<string | undefined>(undefined);
  const [description, setDescription] = useState<string>(
    dataset ? dataset.description || "" : "",
  );
  const [runsPerItem, setRunsPerItem] = useState<number>(1);
  const [passThreshold, setPassThreshold] = useState<number>(1);
  const [assertions, setAssertions] = useState<string[]>([]);

  const runsInput = useClampedIntegerInput({
    value: runsPerItem,
    min: 1,
    max: MAX_RUNS_PER_ITEM,
    onCommit: (v) => {
      setRunsPerItem(v);
      if (passThreshold > v) {
        setPassThreshold(v);
      }
    },
  });

  const thresholdInput = useClampedIntegerInput({
    value: passThreshold,
    min: 1,
    max: runsPerItem,
    onCommit: (v) => setPassThreshold(v),
  });

  useEffect(() => {
    setIsOverlayShown(false);
    setConfirmOpen(false);
    setNameError(undefined);

    if (!open) {
      setCsvFile(undefined);
      setCsvError(undefined);
      setCsvData(undefined);
      setType(DATASET_TYPE.EVALUATION_SUITE);
      setRunsPerItem(1);
      setPassThreshold(1);
      setAssertions([]);
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

  const typeLabel =
    type === DATASET_TYPE.EVALUATION_SUITE ? "evaluation suite" : "dataset";
  const title = isEdit ? "Edit" : "Create new";
  const buttonText = isEdit ? "Update" : "Create new";

  // Determine which mode we're in and set limits accordingly
  const fileSizeLimit = isCsvUploadEnabled
    ? CSV_MODE_FILE_SIZE_LIMIT_IN_MB
    : JSON_MODE_FILE_SIZE_LIMIT_IN_MB;

  const applyEvaluationCriteria = useCallback(
    (datasetId: string, onDone?: () => void) => {
      const filteredAssertions = assertions
        .map((a) => a.trim())
        .filter(Boolean);
      const hasCustomPolicy = runsPerItem !== 1 || passThreshold !== 1;
      const hasAssertions = filteredAssertions.length > 0;

      if (!hasCustomPolicy && !hasAssertions) {
        onDone?.();
        return;
      }

      changesMutate(
        {
          datasetId,
          payload: {
            added_items: [],
            edited_items: [],
            deleted_ids: [],
            base_version: null,
            ...(hasAssertions && {
              evaluators: [packAssertions(filteredAssertions)],
            }),
            ...(hasCustomPolicy && {
              execution_policy: {
                runs_per_item: runsPerItem,
                pass_threshold: passThreshold,
              },
            }),
          },
          override: true,
        },
        {
          onSettled: onDone,
        },
      );
    },
    [assertions, runsPerItem, passThreshold, changesMutate],
  );

  const onCreateSuccessHandler = useCallback(
    (newDataset: Dataset) => {
      const navigateToDataset = () => {
        setOpen(false);
        onDatasetCreated?.(newDataset);
      };

      if (hasValidCsvFile) {
        setIsOverlayShown(true);
      }

      if (hasValidCsvFile && newDataset.id) {
        const applyThenNavigate = () => {
          applyEvaluationCriteria(newDataset.id, navigateToDataset);
        };

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
              onSettled: applyThenNavigate,
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
              onSettled: applyThenNavigate,
            },
          );
        }
      } else {
        // No CSV — wait for evaluation criteria before navigating
        applyEvaluationCriteria(newDataset.id, navigateToDataset);
      }
    },
    [
      applyEvaluationCriteria,
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
            ...(activeProjectId && { project_id: activeProjectId }),
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
    activeProjectId,
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

        // Autofill name from filename if name is empty
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

          // Autofill name from filename if name is empty
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
          <div className="flex flex-col gap-2 pb-4">
            <Label htmlFor="evaluationSuiteName">Name</Label>
            <Input
              id="evaluationSuiteName"
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
            <Label htmlFor="evaluationSuiteDescription">Description</Label>
            <Textarea
              id="evaluationSuiteDescription"
              placeholder="Description"
              className="min-h-20"
              value={description}
              onChange={(event) => setDescription(event.target.value)}
              maxLength={255}
            />
          </div>
          {!isEdit && (
            <>
              <Separator className="mb-4" />
              <div className="mb-4">
                <h3 className="comet-body-accented">Evaluation criteria</h3>
                <p className="comet-body-s text-light-slate">
                  Define the conditions required for the evaluation to pass
                </p>
              </div>
              <div className="mb-4 flex gap-4">
                <div className="flex flex-1 flex-col gap-1">
                  <Label htmlFor="runsPerItem">Default runs per item</Label>
                  <Input
                    id="runsPerItem"
                    dimension="sm"
                    className={cn({
                      "border-destructive": runsInput.isInvalid,
                    })}
                    type="number"
                    min={1}
                    max={MAX_RUNS_PER_ITEM}
                    value={runsInput.displayValue}
                    onChange={runsInput.onChange}
                    onFocus={runsInput.onFocus}
                    onBlur={runsInput.onBlur}
                    onKeyDown={runsInput.onKeyDown}
                  />
                </div>
                <div className="flex flex-1 flex-col gap-1">
                  <Label htmlFor="passThreshold">Default pass threshold</Label>
                  <Input
                    id="passThreshold"
                    dimension="sm"
                    className={cn({
                      "border-destructive": thresholdInput.isInvalid,
                    })}
                    type="number"
                    min={1}
                    max={runsPerItem}
                    value={thresholdInput.displayValue}
                    onChange={thresholdInput.onChange}
                    onFocus={thresholdInput.onFocus}
                    onBlur={thresholdInput.onBlur}
                    onKeyDown={thresholdInput.onKeyDown}
                  />
                </div>
              </div>
              <div className="flex flex-col gap-1 pb-4">
                <div className="mb-1">
                  <Label>Global assertions</Label>
                  <p className="comet-body-s text-light-slate">
                    Define the global conditions all items in this evaluation
                    suite must pass.
                  </p>
                </div>
                <div className="pt-1.5">
                  <AssertionsField
                    editableAssertions={assertions}
                    onChangeEditable={(index, value) => {
                      setAssertions((prev) => {
                        const next = [...prev];
                        next[index] = value;
                        return next;
                      });
                    }}
                    onRemoveEditable={(index) => {
                      setAssertions((prev) =>
                        prev.filter((_, i) => i !== index),
                      );
                    }}
                    onAdd={() => setAssertions((prev) => [...prev, ""])}
                    placeholder="e.g. Response should be factually accurate and cite sources"
                  />
                </div>
              </div>
            </>
          )}
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
                    evaluation suites use the SDK instead.
                  </>
                )}
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
        title="File can't be uploaded"
        description={`This file cannot be uploaded because it does not pass validation. If you continue, the ${typeLabel} will be created without any items. You can add items manually later, or go back and upload a valid file.`}
        cancelText={`Create empty ${typeLabel}`}
        confirmText="Go back"
      />
    </Dialog>
  );
};

export default AddEditEvaluationSuiteDialog;
