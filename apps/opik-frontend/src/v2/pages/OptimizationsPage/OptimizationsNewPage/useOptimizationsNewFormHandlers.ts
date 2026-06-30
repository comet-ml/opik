import { useCallback, useMemo } from "react";
import { useFormContext } from "react-hook-form";

import { useActiveProjectId } from "@/store/AppStore";
import useDatasetById from "@/api/datasets/useDatasetById";
import { METRIC_TYPE } from "@/types/optimizations";
import { PROVIDER_MODEL_TYPE } from "@/types/providers";
import { safelyGetPromptVariables } from "@/lib/prompt";
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

/**
 * Wires the new-run form together: watches, the selected-dataset lookup, the
 * dataset/name handlers, and the per-section handler hooks (optimizer, metric,
 * model, submit). Section logic lives in ./formHandlers/* — this just composes.
 */
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

  const { datasetSample, datasetVariables } = useDatasetSamplePreview({
    datasetId,
  });

  // {{variables}} used in the prompt and the G-Eval metric must exist as
  // columns in the selected item source — otherwise they resolve to nothing
  // and the run fails at evaluation time. We surface the offenders so the form
  // can block submit with a clear reason (e.g. after switching datasets, a
  // template's {{question}}/{{expected_behavior}} no longer match the columns).
  // Skipped until the sample has loaded (empty `datasetVariables`) so we don't
  // flag a mismatch before we know the columns.
  const missingDatasetVariables = useMemo(() => {
    if (datasetVariables.length === 0) return [];

    const referenced = new Set<string>();
    const collect = (text: unknown) => {
      if (typeof text !== "string" || text.length === 0) return;
      safelyGetPromptVariables(text).forEach((tag) => referenced.add(tag));
    };

    (messages ?? []).forEach((message) =>
      collect(
        typeof message.content === "string" ? message.content : undefined,
      ),
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

  // Resolve the selected dataset's name on demand instead of scanning a
  // capped list of every project dataset. Loading/error are surfaced so submit
  // is gated on the lookup rather than silently no-oping when it's in flight or
  // the dataset is gone (e.g. a rerun whose dataset was deleted).
  const {
    data: selectedDataset,
    isLoading: isDatasetLoading,
    isError: isDatasetError,
  } = useDatasetById({ datasetId }, { enabled: Boolean(datasetId) });

  const handleDatasetChange = useCallback(
    (id: string | null) => {
      form.setValue("datasetId", id || "", {
        shouldValidate: true,
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
          { shouldValidate: true, shouldDirty: true },
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
  const { isSubmitting, handleSubmit } = useSubmitOptimization({
    form,
    selectedDataset,
  });

  return {
    form,
    isSubmitting,
    activeProjectId,
    datasetId,
    optimizerType,
    metricType,
    model,
    config,
    datasetSample,
    datasetVariables,
    missingDatasetVariables,
    isDatasetLoading,
    isDatasetError,
    handleDatasetChange,
    handleOptimizerTypeChange,
    handleOptimizerParamsChange,
    handleMetricTypeChange,
    handleMetricParamsChange,
    handleModelConfigChange,
    handleModelChange,
    handleSubmit,
    handleNameChange,
    getFirstMetricParamsError,
  };
};
