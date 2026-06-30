import { useCallback } from "react";
import { UseFormReturn } from "react-hook-form";

import { PROVIDER_MODEL_TYPE, LLMPromptConfigsType } from "@/types/providers";
import { getOptimizationDefaultConfigByProvider } from "@/lib/optimizations";
import { updateProviderConfig } from "@/lib/modelUtils";
import useLLMProviderModelsData from "@/hooks/useLLMProviderModelsData";
import { OptimizationConfigFormType } from "@/v2/pages-shared/optimizations/OptimizationConfigForm/schema";

/** Model section: model selection (with provider-aware defaults) + config edits. */
export const useModelFormHandlers = (
  form: UseFormReturn<OptimizationConfigFormType>,
) => {
  const { calculateModelProvider } = useLLMProviderModelsData();

  const handleModelChange = useCallback(
    (newModel: PROVIDER_MODEL_TYPE) => {
      const newProvider = calculateModelProvider(newModel);
      const defaultConfig = getOptimizationDefaultConfigByProvider(
        newProvider,
        newModel,
      );
      // Strip params the model doesn't accept (e.g. temperature on models that
      // deprecate it), matching the playground's reconciler.
      const adjustedConfig =
        updateProviderConfig(defaultConfig, {
          model: newModel,
          provider: newProvider,
        }) ?? defaultConfig;

      form.setValue("modelName", newModel, { shouldDirty: true });
      form.setValue(
        "modelConfig",
        adjustedConfig as OptimizationConfigFormType["modelConfig"],
        { shouldDirty: true },
      );
    },
    [form, calculateModelProvider],
  );

  const handleModelConfigChange = useCallback(
    (newConfigs: Partial<LLMPromptConfigsType>) => {
      const currentConfig = form.getValues("modelConfig");
      form.setValue(
        "modelConfig",
        {
          ...currentConfig,
          ...newConfigs,
        } as typeof currentConfig,
        { shouldDirty: true },
      );
    },
    [form],
  );

  return { handleModelChange, handleModelConfigChange };
};
