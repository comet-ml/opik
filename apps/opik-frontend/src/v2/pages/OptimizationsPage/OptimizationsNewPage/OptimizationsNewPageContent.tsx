import React from "react";
import { useOptimizationsNewFormHandlers } from "./useOptimizationsNewFormHandlers";
import OptimizationsNewHeader from "./OptimizationsNewHeader";
import OptimizationsNewPromptSection from "./OptimizationsNewPromptSection";
import OptimizationsNewConfigSidebar from "./OptimizationsNewConfigSidebar";

const OptimizationsNewPageContent: React.FC = () => {
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
    handleCancel,
    handleNameChange,
    getFirstMetricParamsError,
    blueprintRef,
    blueprintPromptName,
    blueprintFieldNames,
    isSavingBlueprint,
    hasUnsavedBlueprintChanges,
    handleBlueprintRefChange,
    handleBlueprintRefClear,
    handleSaveBlueprintExisting,
    handleSaveBlueprintNewField,
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
          projectId={activeProjectId!}
          model={model}
          config={config}
          datasetVariables={datasetVariables}
          onNameChange={handleNameChange}
          onModelChange={handleModelChange}
          onModelConfigChange={handleModelConfigChange}
          blueprintRef={blueprintRef}
          blueprintPromptName={blueprintPromptName}
          blueprintFieldNames={blueprintFieldNames}
          isSavingBlueprint={isSavingBlueprint}
          hasUnsavedBlueprintChanges={hasUnsavedBlueprintChanges}
          onBlueprintRefChange={handleBlueprintRefChange}
          onBlueprintRefClear={handleBlueprintRefClear}
          onSaveBlueprintExisting={handleSaveBlueprintExisting}
          onSaveBlueprintNewField={handleSaveBlueprintNewField}
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
    </div>
  );
};

export default OptimizationsNewPageContent;
