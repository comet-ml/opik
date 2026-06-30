import React from "react";
import { Loader2 } from "lucide-react";
import { Button } from "@/ui/button";
import { useOptimizationsNewFormHandlers } from "./useOptimizationsNewFormHandlers";
import OptimizationsNewPromptSection from "./OptimizationsNewPromptSection";
import OptimizationsNewConfigSidebar from "./OptimizationsNewConfigSidebar";

type OptimizationsNewPageContentProps = {
  onCancel: () => void;
  // True while a template's demo dataset is being created; surfaced inline on
  // the dataset field and the submit button instead of blocking the panel.
  isPreparingDataset: boolean;
};

const OptimizationsNewPageContent: React.FC<
  OptimizationsNewPageContentProps
> = ({ onCancel, isPreparingDataset }) => {
  const {
    form,
    isSubmitting,
    activeProjectId,
    optimizerType,
    metricType,
    model,
    config,
    datasetSample,
    datasetVariables,
    missingDatasetVariables,
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
  } = useOptimizationsNewFormHandlers();

  const hasMissingVariables = missingDatasetVariables.length > 0;

  return (
    <div className="flex size-full flex-col">
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
            onClick={handleSubmit}
            disabled={
              isSubmitting ||
              !form.formState.isValid ||
              isPreparingDataset ||
              hasMissingVariables
            }
          >
            {isSubmitting && <Loader2 className="mr-2 size-4 animate-spin" />}
            {isSubmitting ? "Starting..." : "Optimize prompt"}
          </Button>
          <Button variant="outline" onClick={onCancel} disabled={isSubmitting}>
            Cancel
          </Button>
        </div>
      </div>
    </div>
  );
};

export default OptimizationsNewPageContent;
