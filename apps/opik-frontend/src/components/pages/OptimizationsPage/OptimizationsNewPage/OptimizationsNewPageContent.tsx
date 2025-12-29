import React from "react";
import { useOptimizationsNewFormHandlers } from "./useOptimizationsNewFormHandlers";
import OptimizationsNewHeader from "./OptimizationsNewHeader";
import OptimizationsNewPromptSection from "./OptimizationsNewPromptSection";
import OptimizationsNewConfigSidebar from "./OptimizationsNewConfigSidebar";

const OptimizationsNewPageContent: React.FC = () => {
  const {
    form,
    workspaceName,
    isSubmitting,
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
    handleCancel,
    handleNameChange,
    getFirstMetricParamsError,
  } = useOptimizationsNewFormHandlers();

  return (
    <div className="w-full py-6">
      <OptimizationsNewHeader
        isSubmitting={isSubmitting}
        isFormValid={form.formState.isValid}
        onSubmit={handleSubmit}
        onCancel={handleCancel}
      />

      <div className="flex gap-6">
        <OptimizationsNewPromptSection
          form={form}
          model={model}
          config={config}
          datasetVariables={datasetVariables}
          onNameChange={handleNameChange}
          onModelChange={handleModelChange}
          onModelConfigChange={handleModelConfigChange}
        />

        <OptimizationsNewConfigSidebar
          form={form}
          workspaceName={workspaceName}
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
    </div>
  );
};

export default OptimizationsNewPageContent;
