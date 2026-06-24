import React from "react";
import { Button } from "@/ui/button";
import { Spinner } from "@/ui/spinner";
import { useOptimizationsNewFormHandlers } from "./useOptimizationsNewFormHandlers";
import OptimizationsNewPromptSection from "./OptimizationsNewPromptSection";
import OptimizationsNewConfigSidebar from "./OptimizationsNewConfigSidebar";

type OptimizationsNewPageContentProps = {
  onCancel: () => void;
};

const OptimizationsNewPageContent: React.FC<
  OptimizationsNewPageContentProps
> = ({ onCancel }) => {
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
          onDatasetChange={handleDatasetChange}
          onOptimizerTypeChange={handleOptimizerTypeChange}
          onOptimizerParamsChange={handleOptimizerParamsChange}
          onMetricTypeChange={handleMetricTypeChange}
          onMetricParamsChange={handleMetricParamsChange}
          getFirstMetricParamsError={getFirstMetricParamsError}
        />
      </div>

      <div className="flex items-center gap-2 border-t px-6 py-4">
        <Button
          onClick={handleSubmit}
          disabled={isSubmitting || !form.formState.isValid}
        >
          {isSubmitting && <Spinner size="small" className="mr-2" />}
          {isSubmitting ? "Starting..." : "Optimize prompt"}
        </Button>
        <Button variant="outline" onClick={onCancel} disabled={isSubmitting}>
          Cancel
        </Button>
      </div>
    </div>
  );
};

export default OptimizationsNewPageContent;
