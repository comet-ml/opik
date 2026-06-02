import { useCallback, useEffect, useState } from "react";
import { AxiosError, HttpStatusCode } from "axios";
import get from "lodash/get";

import { useActiveProjectId } from "@/store/AppStore";
import useDatasetCreateMutation from "@/api/datasets/useDatasetCreateMutation";
import useDatasetItemsFromCsvMutation from "@/api/datasets/useDatasetItemsFromCsvMutation";
import useDatasetItemsFromJsonMutation from "@/api/datasets/useDatasetItemsFromJsonMutation";
import useDatasetUpdateMutation from "@/api/datasets/useDatasetUpdateMutation";
import useDatasetItemChangesMutation from "@/api/datasets/useDatasetItemChangesMutation";
import { useToast } from "@/ui/use-toast";
import {
  detectUploadFormat,
  formatToHumanLabel,
  getDatasetUploadFilenameWithoutExtension,
  UploadFormat,
} from "@/lib/file";
import { packAssertions } from "@/lib/assertion-converters";
import { Dataset, DATASET_TYPE } from "@/types/datasets";
import { MAX_RUNS_PER_ITEM } from "@/types/test-suites";
import { useClampedIntegerInput } from "@/hooks/useClampedIntegerInput";
import { OpikEvent, trackEvent } from "@/lib/analytics/tracking";

const FILE_SIZE_LIMIT_IN_MB = 2000;

type UseDatasetFormParams = {
  dataset?: Dataset;
  open: boolean;
  setOpen: (open: boolean) => void;
  onDatasetCreated?: (dataset: Dataset) => void;
  hideUpload?: boolean;
  csvRequired?: boolean;
  skipEvaluationCriteria?: boolean;
  datasetType: DATASET_TYPE;
  onNameConflict?: () => void;
  onCreateSuccess?: (dataset: Dataset, navigate: () => void) => void;
};

const useDatasetForm = ({
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
}: UseDatasetFormParams) => {
  const activeProjectId = useActiveProjectId();
  const { toast } = useToast();

  const { mutate: createMutate } = useDatasetCreateMutation();
  const { mutate: updateMutate } = useDatasetUpdateMutation();
  const { mutate: createItemsFromCsvMutate } = useDatasetItemsFromCsvMutation();
  const { mutate: createItemsFromJsonMutate } =
    useDatasetItemsFromJsonMutation();
  const { mutate: changesMutate } = useDatasetItemChangesMutation();

  const [confirmOpen, setConfirmOpen] = useState(false);
  const [uploadFile, setUploadFile] = useState<File | undefined>(undefined);
  const [uploadError, setUploadError] = useState<string | undefined>(undefined);
  const [uploadFormat, setUploadFormat] = useState<UploadFormat | undefined>(
    undefined,
  );

  const [type, setType] = useState<DATASET_TYPE>(datasetType);
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
    setConfirmOpen(false);
    setNameError(undefined);

    if (!open) {
      const timeout = setTimeout(() => {
        setUploadFile(undefined);
        setUploadError(undefined);
        setUploadFormat(undefined);
        setType(datasetType);
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
  }, [open, dataset, datasetType]);

  const isEdit = Boolean(dataset);
  const hasValidUploadFile = uploadFile && !uploadError;
  const isValid =
    name.length > 0 &&
    (isEdit || hideUpload || !csvRequired || hasValidUploadFile);

  const typeLabel = type === DATASET_TYPE.TEST_SUITE ? "test suite" : "dataset";
  const title = isEdit ? "Edit" : "Create new";
  const buttonText = isEdit ? "Update" : "Create new";

  const fileSizeLimit = FILE_SIZE_LIMIT_IN_MB;

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
      if (!uploadFile || !uploadFormat) {
        onDone();
        return;
      }
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
          const serverMessage = get(error, ["response", "data", "message"]);
          const serverErrors = get(error, ["response", "data", "errors"]);
          const joinedErrors = Array.isArray(serverErrors)
            ? serverErrors.join(", ")
            : undefined;
          const errorMessage =
            [serverMessage, joinedErrors].filter(Boolean).join(": ") ||
            get(error, ["message"]) ||
            `Failed to upload ${label} file`;
          toast({
            title: `Error uploading ${label} file`,
            description: errorMessage,
            variant: "destructive",
          });
        },
        onSettled: onDone,
      };

      if (uploadFormat === "csv") {
        createItemsFromCsvMutate({ datasetId, csvFile: uploadFile }, handlers);
      } else {
        createItemsFromJsonMutate(
          { datasetId, jsonFile: uploadFile, format: uploadFormat },
          handlers,
        );
      }
    },
    [
      uploadFile,
      uploadFormat,
      createItemsFromCsvMutate,
      createItemsFromJsonMutate,
      toast,
    ],
  );

  const onCreateSuccessHandler = useCallback(
    (newDataset: Dataset) => {
      if (datasetType === DATASET_TYPE.TEST_SUITE) {
        trackEvent(OpikEvent.EVAL_SUITE_UI_CONFIGURED, {
          eval_suite_id: newDataset.id,
          eval_suite_name: newDataset.name,
          has_csv_upload: hasValidUploadFile && uploadFormat === "csv",
          upload_format: hasValidUploadFile ? uploadFormat : undefined,
          num_assertions: assertions.filter((a) => a.trim()).length,
          runs_per_item: runsPerItem,
        });
      }

      const navigateToDataset = () => {
        setOpen(false);
        onDatasetCreated?.(newDataset);
      };

      const finalize = onCreateSuccess
        ? () => onCreateSuccess(newDataset, navigateToDataset)
        : navigateToDataset;

      if (hasValidUploadFile && newDataset.id) {
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
      hasValidUploadFile,
      uploadFormat,
      onDatasetCreated,
      onCreateSuccess,
      setOpen,
      datasetType,
      assertions,
      runsPerItem,
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
    (file?: File) => {
      setUploadError(undefined);
      setUploadFile(undefined);
      setUploadFormat(undefined);

      if (!file) return;

      if (file.size > fileSizeLimit * 1024 * 1024) {
        setUploadError(`File exceeds maximum size (${fileSizeLimit}MB).`);
        return;
      }
      const format = detectUploadFormat(file.name);
      if (!format) {
        setUploadError("File must be .csv, .json, .jsonl, or .ndjson");
        return;
      }
      setUploadFile(file);
      setUploadFormat(format);
      if (!name.trim()) {
        setName(getDatasetUploadFilenameWithoutExtension(file.name));
      }
    },
    [fileSizeLimit, name],
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
    uploadFile,
    uploadError,
    uploadFormat,
    isEdit,
    isValid,
    confirmOpen,
    setConfirmOpen,
    fileSizeLimit,
    typeLabel,
    title,
    buttonText,
    submitHandler,
    handleFileSelect,
  };
};

export default useDatasetForm;
