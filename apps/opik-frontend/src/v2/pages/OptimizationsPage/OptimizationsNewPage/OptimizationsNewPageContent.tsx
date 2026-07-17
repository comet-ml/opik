import React, { useCallback } from "react";
import { Loader2 } from "lucide-react";
import { Button } from "@/ui/button";
import { useOptimizationsNewFormHandlers } from "./useOptimizationsNewFormHandlers";
import OptimizationsNewPromptSection from "./OptimizationsNewPromptSection";
import OptimizationsNewConfigSidebar from "./OptimizationsNewConfigSidebar";

// The form body lives outside this <form> so only the footer's submit button
// (linked via form={FORM_ID}) can submit it. A native <button> inside a <form>
// defaults to type="submit", so keeping the dropdowns/settings buttons out of
// the form stops them from triggering a submit on click.
const FORM_ID = "new-optimization-run-form";

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
    datasetId,
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
  } = useOptimizationsNewFormHandlers();

  const hasMissingVariables = missingDatasetVariables.length > 0;

  const { isSubmitting } = form.formState;

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
    <div className="flex size-full flex-col">
      <form id={FORM_ID} onSubmit={handleFormSubmit} />
      <div className="flex flex-1 flex-col gap-6 overflow-y-auto px-5 py-4 xl:flex-row">
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
        />
      </div>

      <div className="flex flex-col gap-2 border-t px-5 py-4">
        {isDatasetError && (
          <span className="comet-body-s text-destructive">
            Couldn&apos;t load the selected item source. Pick another or try
            again.
          </span>
        )}
        {hasMissingVariables && (
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
            form={FORM_ID}
            size="sm"
            disabled={
              isSubmitting ||
              isPreparingDataset ||
              isDatasetLoading ||
              isDatasetError ||
              !model ||
              !datasetId ||
              hasMissingVariables
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
            size="sm"
            type="button"
            onClick={onCancel}
            disabled={isSubmitting}
          >
            Cancel
          </Button>
        </div>
      </div>
    </div>
  );
};

export default OptimizationsNewPageContent;
