import { useCallback, useEffect, useState } from "react";
import { AxiosError, HttpStatusCode } from "axios";
import get from "lodash/get";

import useAppStore, { useActiveProjectId } from "@/store/AppStore";
import useDatasetCreateMutation from "@/api/datasets/useDatasetCreateMutation";
import useDatasetItemBatchMutation from "@/api/datasets/useDatasetItemBatchMutation";
import useDatasetItemsFromCsvMutation from "@/api/datasets/useDatasetItemsFromCsvMutation";
import useDatasetUpdateMutation from "@/api/datasets/useDatasetUpdateMutation";
import useDatasetItemChangesMutation from "@/api/datasets/useDatasetItemChangesMutation";
import { useToast } from "@/ui/use-toast";
import { validateCsvFile, getCsvFilenameWithoutExtension } from "@/lib/file";
import { packAssertions } from "@/lib/assertion-converters";
import { Dataset, DATASET_TYPE, DATASET_ITEM_SOURCE } from "@/types/datasets";
import { MAX_RUNS_PER_ITEM } from "@/types/evaluation-suites";
import { FeatureToggleKeys } from "@/types/feature-toggles";
import { useIsFeatureEnabled } from "@/contexts/feature-toggles-provider";
import { useClampedIntegerInput } from "@/hooks/useClampedIntegerInput";

const JSON_MODE_FILE_SIZE_LIMIT_IN_MB = 20;
const JSON_MODE_MAX_ITEMS = 1000;
const CSV_MODE_FILE_SIZE_LIMIT_IN_MB = 2000;

type UseEvaluationSuiteFormParams = {
  dataset?: Dataset;
  open: boolean;
  setOpen: (open: boolean) => void;
  onDatasetCreated?: (dataset: Dataset) => void;
  hideUpload?: boolean;
  csvRequired?: boolean;
  skipEvaluationCriteria?: boolean;
  datasetType?: DATASET_TYPE;
  onNameConflict?: () => void;
  onCreateSuccess?: (dataset: Dataset, navigate: () => void) => void;
};

const useEvaluationSuiteForm = ({
  dataset,
  open,
  setOpen,
  onDatasetCreated,
  hideUpload,
  csvRequired = false,
  skipEvaluationCriteria = false,
  datasetType,
  onNameConflict,
  onCreateSuccess,
}: UseEvaluationSuiteFormParams) => {
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

  const [isOverlayShown, setIsOverlayShown] = useState(false);
  const [confirmOpen, setConfirmOpen] = useState(false);
  const [csvFile, setCsvFile] = useState<File | undefined>(undefined);
  const [csvData, setCsvData] = useState<Record<string, unknown>[] | undefined>(
    undefined,
  );
  const [csvError, setCsvError] = useState<string | undefined>(undefined);

  const [type, setType] = useState<DATASET_TYPE>(DATASET_TYPE.EVALUATION_SUITE);
  const [name, setName] = useState(dataset ? dataset.name : "");
  const [nameError, setNameError] = useState<string | undefined>(undefined);
  const [description, setDescription] = useState(
    dataset ? dataset.description || "" : "",
  );
  const [runsPerItem, setRunsPerItem] = useState(1);
  const [passThreshold, setPassThreshold] = useState(1);
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
      const timeout = setTimeout(() => {
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
      }, 200);
      return () => clearTimeout(timeout);
    } else if (dataset) {
      setName(dataset.name);
      setDescription(dataset.description || "");
    }
  }, [open, dataset]);

  const isEdit = Boolean(dataset);
  const hasValidCsvFile = csvFile && !csvError;
  const isValid =
    name.length > 0 &&
    (isEdit || hideUpload || !csvRequired || hasValidCsvFile);

  const typeLabel =
    type === DATASET_TYPE.EVALUATION_SUITE ? "evaluation suite" : "dataset";
  const title = isEdit ? "Edit" : "Create new";
  const buttonText = isEdit ? "Update" : "Create new";

  const fileSizeLimit = isCsvUploadEnabled
    ? CSV_MODE_FILE_SIZE_LIMIT_IN_MB
    : JSON_MODE_FILE_SIZE_LIMIT_IN_MB;

  const applyEvaluationCriteria = useCallback(
    (datasetId: string, onDone?: () => void) => {
      if (skipEvaluationCriteria) {
        onDone?.();
        return;
      }

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
    [
      skipEvaluationCriteria,
      assertions,
      runsPerItem,
      passThreshold,
      changesMutate,
    ],
  );

  const uploadItems = useCallback(
    (datasetId: string, onDone: () => void) => {
      if (isCsvUploadEnabled && csvFile) {
        createItemsFromCsvMutate(
          { datasetId, csvFile },
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
            onSettled: onDone,
          },
        );
      } else if (!isCsvUploadEnabled && csvData) {
        createItemsMutate(
          {
            datasetId,
            workspaceName,
            datasetItems: csvData.map((row) => ({
              data: row,
              source: DATASET_ITEM_SOURCE.manual,
            })),
          },
          { onSettled: onDone },
        );
      }
    },
    [
      isCsvUploadEnabled,
      csvFile,
      csvData,
      createItemsFromCsvMutate,
      createItemsMutate,
      workspaceName,
      toast,
    ],
  );

  const onCreateSuccessHandler = useCallback(
    (newDataset: Dataset) => {
      const navigateToDataset = () => {
        setOpen(false);
        onDatasetCreated?.(newDataset);
      };

      const finalize = onCreateSuccess
        ? () => onCreateSuccess(newDataset, navigateToDataset)
        : navigateToDataset;

      if (hasValidCsvFile) {
        setIsOverlayShown(true);
      }

      if (hasValidCsvFile && newDataset.id) {
        const uploadThenFinalize = () => {
          uploadItems(newDataset.id, finalize);
        };
        applyEvaluationCriteria(newDataset.id, uploadThenFinalize);
      } else {
        applyEvaluationCriteria(newDataset.id, finalize);
      }
    },
    [
      applyEvaluationCriteria,
      uploadItems,
      hasValidCsvFile,
      onDatasetCreated,
      onCreateSuccess,
      setOpen,
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
        onNameConflict?.();
      } else {
        toast({
          title: "Error saving",
          description: errorMessage || `Failed to ${action}`,
          variant: "destructive",
        });
        setOpen(false);
      }
    },
    [toast, setOpen, onNameConflict],
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
            type: datasetType ?? type,
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
    datasetType,
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

      if (!file) return;

      if (isCsvUploadEnabled) {
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
      } else {
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
          if (!name.trim()) {
            setName(getCsvFilenameWithoutExtension(file.name));
          }
        }
      }
    },
    [isCsvUploadEnabled, fileSizeLimit, name],
  );

  return {
    name,
    setName,
    nameError,
    setNameError,
    description,
    setDescription,
    assertions,
    setAssertions,
    runsPerItem,
    runsInput,
    thresholdInput,
    csvFile,
    csvError,
    isEdit,
    isValid,
    isOverlayShown,
    setIsOverlayShown,
    confirmOpen,
    setConfirmOpen,
    isCsvUploadEnabled,
    fileSizeLimit,
    typeLabel,
    title,
    buttonText,
    submitHandler,
    handleFileSelect,
  };
};

export default useEvaluationSuiteForm;
