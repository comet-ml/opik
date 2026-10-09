import { useCallback } from "react";
import { UseFormReturn } from "react-hook-form";

import { PROVIDER_MODEL_TYPE, LLMPromptConfigsType } from "@/types/providers";
import { getOptimizationDefaultConfigByProvider } from "@/lib/optimizations";
import { updateProviderConfig } from "@/lib/modelUtils";
import useLLMProviderModelsData from "@/hooks/useLLMProviderModelsData";
import { OptimizationConfigFormType } from "@/v2/pages-shared/optimizations/OptimizationConfigForm/schema";

const THINKING_AMOUNT_KEYS = [
  "reasoningEffort",
  "thinkingEffort",
  "thinkingLevel",
] as const;

export const useModelFormHandlers = (
  form: UseFormReturn<OptimizationConfigFormType>,
) => {
  const { calculateModelProvider } = useLLMProviderModelsData();

  const handleModelChange = useCallback(
    (newModel: PROVIDER_MODEL_TYPE) => {
      const newProvider = calculateModelProvider(newModel);
      const previousModel = form.getValues("modelName") as PROVIDER_MODEL_TYPE;
      const defaultConfig = getOptimizationDefaultConfigByProvider(
        newProvider,
        newModel,
      );
      // Within a provider, the picked effort or thinking level is carried over, as in the
      // playground, so updateProviderConfig can move it to the nearest level the new model offers.
      const sameProvider =
        Boolean(previousModel) &&
        calculateModelProvider(previousModel) === newProvider;
      const previousConfig = (form.getValues("modelConfig") ?? {}) as Record<
        string,
        unknown
      >;
      const pickedLevels = sameProvider
        ? Object.fromEntries(
            THINKING_AMOUNT_KEYS.filter(
              (key) => previousConfig[key] !== undefined,
            ).map((key) => [key, previousConfig[key]]),
          )
        : {};
      const startConfig = { ...defaultConfig, ...pickedLevels };
      // Strip params the model doesn't accept (e.g. temperature on models that
      // deprecate it), matching the playground's reconciler.
      const adjustedConfig =
        updateProviderConfig(startConfig, {
          model: newModel,
          provider: newProvider,
          previousModel: sameProvider ? previousModel : undefined,
        }) ?? startConfig;

      form.setValue("modelName", newModel, { shouldDirty: true });
      form.setValue(
        "modelConfig",
        adjustedConfig as OptimizationConfigFormType["modelConfig"],
        { shouldDirty: true },
      );

      // The algorithm model is left untouched here: an unset algorithm model
      // inherits the (new) prompt model at runtime, and an explicitly-set one is
      // the user's deliberate choice — syncing it to the prompt model would
      // silently discard that choice.
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
