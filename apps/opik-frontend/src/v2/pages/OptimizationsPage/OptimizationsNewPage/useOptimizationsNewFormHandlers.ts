import { useCallback } from "react";
import { useFormContext } from "react-hook-form";

import { useActiveProjectId } from "@/store/AppStore";
import useDatasetById from "@/api/datasets/useDatasetById";
import { METRIC_TYPE } from "@/types/optimizations";
import { PROVIDER_MODEL_TYPE } from "@/types/providers";
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
  const model = form.watch("modelName") as PROVIDER_MODEL_TYPE | "";
  const config = form.watch("modelConfig");

  const { datasetSample, datasetVariables } = useDatasetSamplePreview({
    datasetId,
  });

  // Resolve the selected dataset's name on demand instead of scanning a
  // capped list of every project dataset.
  const { data: selectedDataset } = useDatasetById(
    { datasetId },
    { enabled: Boolean(datasetId) },
  );

  const handleDatasetChange = useCallback(
    (id: string | null) => {
      form.setValue("datasetId", id || "", { shouldValidate: true });

      // Reference keys are dataset-specific, so clear them when the dataset
      // changes for metrics that use one.
      if (METRICS_WITH_REFERENCE_KEY.includes(form.getValues("metricType"))) {
        form.setValue(
          "metricParams",
          {
            ...form.getValues("metricParams"),
            reference_key: "",
          } as OptimizationConfigFormType["metricParams"],
          { shouldValidate: true },
        );
      }
    },
    [form],
  );

  const handleNameChange = useCallback(
    (value: string) => form.setValue("name", value),
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
