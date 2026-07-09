import { useCallback, useMemo } from "react";
import { useFormContext } from "react-hook-form";

import { useActiveProjectId } from "@/store/AppStore";
import useDatasetById from "@/api/datasets/useDatasetById";
import { METRIC_TYPE } from "@/types/optimizations";
import { PROVIDER_MODEL_TYPE } from "@/types/providers";
import { extractMessageContent, safelyGetPromptVariables } from "@/lib/prompt";
import { OptimizationConfigFormType } from "@/v2/pages-shared/optimizations/OptimizationConfigForm/schema";
import useDatasetSamplePreview from "./useDatasetSamplePreview";
import { useOptimizerFormHandlers } from "./formHandlers/useOptimizerFormHandlers";
import { useMetricFormHandlers } from "./formHandlers/useMetricFormHandlers";
import { useModelFormHandlers } from "./formHandlers/useModelFormHandlers";
import { useSubmitOptimization } from "./formHandlers/useSubmitOptimization";

const METRICS_WITH_REFERENCE_KEY = [
  METRIC_TYPE.EQUALS,
  METRIC_TYPE.JSON_SCHEMA_VALIDATOR,
  METRIC_TYPE.LEVENSHTEIN,
];

export const useOptimizationsNewFormHandlers = () => {
  const activeProjectId = useActiveProjectId();
  const form = useFormContext<OptimizationConfigFormType>();

  const datasetId = form.watch("datasetId");
  const optimizerType = form.watch("optimizerType");
  const metricType = form.watch("metricType");
  const metricParams = form.watch("metricParams");
  const messages = form.watch("messages");
  const model = form.watch("modelName") as PROVIDER_MODEL_TYPE | "";
  const config = form.watch("modelConfig");

  const { datasetSample, datasetVariables, areColumnsLoading } =
    useDatasetSamplePreview({
      datasetId,
    });

  // Prompt + G-Eval `{{variables}}` must exist as dataset columns, or the run
  // fails at evaluation time. Skipped until the sample loads (empty
  // `datasetVariables`) so we don't flag a mismatch before the columns are known.
  const missingDatasetVariables = useMemo(() => {
    if (datasetVariables.length === 0) return [];

    const referenced = new Set<string>();
    const collect = (text: unknown) => {
      if (typeof text !== "string" || text.length === 0) return;
      safelyGetPromptVariables(text).forEach((tag) => referenced.add(tag));
    };

    // Flatten structured (array) message content to text so `{{vars}}` inside
    // multipart parts are still checked, not just plain-string content.
    (messages ?? []).forEach((message) =>
      collect(extractMessageContent(message.content)),
    );

    if (metricType === METRIC_TYPE.G_EVAL && metricParams) {
      const params = metricParams as {
        task_introduction?: string;
        evaluation_criteria?: string;
      };
      collect(params.task_introduction);
      collect(params.evaluation_criteria);
    }

    return [...referenced].filter((tag) => !datasetVariables.includes(tag));
  }, [messages, metricParams, metricType, datasetVariables]);

  // Resolve the dataset by id (not from a capped list). Loading/error gate
  // submit so an in-flight or deleted dataset can't silently no-op.
  const {
    data: selectedDataset,
    isLoading: isDatasetLoading,
    isError: isDatasetError,
  } = useDatasetById({ datasetId }, { enabled: Boolean(datasetId) });

  const handleDatasetChange = useCallback(
    (id: string | null) => {
      form.setValue("datasetId", id || "", {
        shouldDirty: true,
      });

      // Reference keys are dataset-specific, so clear them when the dataset
      // changes for metrics that use one.
      if (METRICS_WITH_REFERENCE_KEY.includes(form.getValues("metricType"))) {
        form.setValue(
          "metricParams",
          {
            ...form.getValues("metricParams"),
            reference_key: "",
          } as OptimizationConfigFormType["metricParams"],
          { shouldDirty: true },
        );
      }
    },
    [form],
  );

  const handleNameChange = useCallback(
    (value: string) => form.setValue("name", value, { shouldDirty: true }),
    [form],
  );

  const { handleOptimizerTypeChange, handleOptimizerParamsChange } =
    useOptimizerFormHandlers(form);
  const {
    handleMetricTypeChange,
    handleMetricParamsChange,
    getFirstMetricParamsError,
  } = useMetricFormHandlers(form);
  const { handleModelChange, handleModelConfigChange } =
    useModelFormHandlers(form);
  const { submitOptimization } = useSubmitOptimization({
    form,
    selectedDataset,
  });

  return {
    form,
    activeProjectId,
    datasetId,
    optimizerType,
    metricType,
    model,
    config,
    datasetSample,
    datasetVariables,
    missingDatasetVariables,
    // Gate submit while EITHER the dataset record or its columns are still
    // loading, so the missing-variable check can't be bypassed mid-load.
    isDatasetLoading: isDatasetLoading || areColumnsLoading,
    isDatasetError,
    handleDatasetChange,
    handleOptimizerTypeChange,
    handleOptimizerParamsChange,
    handleMetricTypeChange,
    handleMetricParamsChange,
    handleModelConfigChange,
    handleModelChange,
    submitOptimization,
    handleNameChange,
    getFirstMetricParamsError,
  };
};
