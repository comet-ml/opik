import React, { useCallback } from "react";
import { Loader2 } from "lucide-react";
import { Button } from "@/ui/button";
import { useOptimizationsNewFormHandlers } from "./useOptimizationsNewFormHandlers";
import OptimizationsNewPromptSection from "./OptimizationsNewPromptSection";
import OptimizationsNewConfigSidebar from "./OptimizationsNewConfigSidebar";

type OptimizationsNewPageContentProps = {
  onCancel: () => void;
  isPreparingDataset: boolean;
};

const OptimizationsNewPageContent: React.FC<
  OptimizationsNewPageContentProps
> = ({ onCancel, isPreparingDataset }) => {
  const {
    form,
    activeProjectId,
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
    submitOptimization,
    handleNameChange,
    getFirstMetricParamsError,
  } = useOptimizationsNewFormHandlers();

  const hasMissingVariables = missingDatasetVariables.length > 0;

  const { isSubmitting, isSubmitted: submitAttempted } = form.formState;

  // The variable-mismatch check lives outside the zod schema, so guard it here
  // even though RHF has already validated the fields.
  const onValid = useCallback(() => {
    if (hasMissingVariables) return;
    return submitOptimization();
  }, [hasMissingVariables, submitOptimization]);

  // handleSubmit re-throws when the submit handler rejects; the create mutation
  // already toasts API errors, so swallow it to avoid an unhandled rejection.
  const handleFormSubmit = useCallback(
    (event: React.FormEvent) => {
      void form
        .handleSubmit(onValid)(event)
        .catch(() => {});
    },
    [form, onValid],
  );

  return (
    <form onSubmit={handleFormSubmit} className="flex size-full flex-col">
      <div className="flex flex-1 flex-col gap-6 overflow-y-auto px-6 pb-6 pt-4 xl:flex-row">
        <OptimizationsNewPromptSection
          form={form}
          projectId={activeProjectId!}
          model={model}
          config={config}
          datasetVariables={datasetVariables}
          onNameChange={handleNameChange}
          onModelChange={handleModelChange}
          onModelConfigChange={handleModelConfigChange}
        />

        <OptimizationsNewConfigSidebar
          form={form}
          projectId={activeProjectId}
          optimizerType={optimizerType}
          metricType={metricType}
          datasetSample={datasetSample}
          datasetVariables={datasetVariables}
          isPreparingDataset={isPreparingDataset}
          onDatasetChange={handleDatasetChange}
          onOptimizerTypeChange={handleOptimizerTypeChange}
          onOptimizerParamsChange={handleOptimizerParamsChange}
          onMetricTypeChange={handleMetricTypeChange}
          onMetricParamsChange={handleMetricParamsChange}
          getFirstMetricParamsError={getFirstMetricParamsError}
        />
      </div>

      <div className="flex flex-col gap-2 border-t px-6 py-4">
        {isDatasetError && (
          <span className="comet-body-s text-destructive">
            Couldn&apos;t load the selected item source. Pick another or try
            again.
          </span>
        )}
        {submitAttempted && hasMissingVariables && (
          <span className="comet-body-s text-destructive">
            {missingDatasetVariables.map((v) => `{{${v}}}`).join(", ")} not in
            the selected item source
            {datasetVariables.length > 0 &&
              ` — available: ${datasetVariables.join(", ")}`}
            . Update the prompt/metric or pick a matching item source.
          </span>
        )}
        <div className="flex items-center gap-2">
          <Button
            type="submit"
            disabled={
              isSubmitting ||
              isPreparingDataset ||
              isDatasetLoading ||
              isDatasetError
            }
          >
            {isSubmitting && (
              <span className="mr-2 inline-flex animate-spin">
                <Loader2 className="size-4" />
              </span>
            )}
            {isSubmitting ? "Starting..." : "Optimize prompt"}
          </Button>
          <Button
            variant="outline"
            type="button"
            onClick={onCancel}
            disabled={isSubmitting}
          >
            Cancel
          </Button>
        </div>
      </div>
    </form>
  );
};

export default OptimizationsNewPageContent;
