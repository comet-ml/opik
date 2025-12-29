import { useCallback, useMemo } from "react";
import useLastPickedModel from "@/hooks/useLastPickedModel";
import useLLMProviderModelsData from "@/hooks/useLLMProviderModelsData";
import useProviderKeys from "@/api/provider-keys/useProviderKeys";
import useAppStore from "@/store/AppStore";
import { getDefaultConfigByProvider } from "@/lib/playground";
import {
  COMPOSED_PROVIDER_TYPE,
  LLMPromptConfigsType,
  PROVIDER_MODEL_TYPE,
} from "@/types/providers";

export interface UseModelSelectionParams {
  /** Unique key for persisting model selection in localStorage */
  persistenceKey: string;
  /** Optional default model to use if no persisted selection exists */
  defaultModel?: string;
  /** Optional default provider to use if no persisted selection exists */
  defaultProvider?: COMPOSED_PROVIDER_TYPE | "";
  /** Optional default configs to use with the default model */
  defaultConfigs?: LLMPromptConfigsType;
}

export interface ModelSelectProps {
  value: PROVIDER_MODEL_TYPE | "";
  provider: COMPOSED_PROVIDER_TYPE | "";
  onChange: (
    model: PROVIDER_MODEL_TYPE,
    provider: COMPOSED_PROVIDER_TYPE,
  ) => void;
  onAddProvider: (provider: COMPOSED_PROVIDER_TYPE) => void;
  onDeleteProvider: (provider: COMPOSED_PROVIDER_TYPE) => void;
}

export interface UseModelSelectionResult {
  /** The currently selected model */
  model: PROVIDER_MODEL_TYPE | "";
  /** The provider for the selected model */
  provider: COMPOSED_PROVIDER_TYPE | "";
  /** The configs for the selected model */
  configs: LLMPromptConfigsType;
  /** Props to spread directly onto PromptModelSelect (except workspaceName) */
  modelSelectProps: ModelSelectProps;
}

/**
 * A reusable hook for model selection with local storage persistence.
 *
 * This hook encapsulates the common pattern of:
 * 1. Persisting the user's model selection in localStorage
 * 2. Fetching available provider keys
 * 3. Calculating the effective model/provider based on what's available
 * 4. Providing handlers for model changes and provider management
 *
 * @example
 * ```tsx
 * const { model, provider, configs, modelSelectProps } = useModelSelection({
 *   persistenceKey: "my-feature-model",
 *   defaultModel: playgroundModel,
 *   defaultProvider: playgroundProvider,
 *   defaultConfigs: playgroundConfigs,
 * });
 *
 * // Use model, provider, configs in your feature logic
 * // Spread modelSelectProps onto PromptModelSelect
 * <PromptModelSelect workspaceName={workspaceName} {...modelSelectProps} />
 * ```
 */
const useModelSelection = ({
  persistenceKey,
  defaultModel,
  defaultProvider,
  defaultConfigs,
}: UseModelSelectionParams): UseModelSelectionResult => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);

  // Persisted model selection
  const [lastPickedModel, setLastPickedModel] = useLastPickedModel({
    key: persistenceKey,
  });

  // Fetch available providers
  const { data: providerKeysData } = useProviderKeys({
    workspaceName,
  });

  const providerKeys = useMemo(() => {
    return providerKeysData?.content?.map((c) => c.ui_composed_provider) || [];
  }, [providerKeysData]);

  // Model/provider resolution utilities
  const { calculateModelProvider, calculateDefaultModel } =
    useLLMProviderModelsData();

  // Calculate the effective model, provider, and configs
  // Priority: 1) User's persisted model (if valid), 2) Provided defaults, 3) Calculated default
  const { model, provider, configs } = useMemo(() => {
    // If user has a persisted model selection, use it if it's still valid
    if (lastPickedModel && providerKeys.length > 0) {
      const lastPickedProvider = calculateModelProvider(lastPickedModel);
      if (lastPickedProvider && providerKeys.includes(lastPickedProvider)) {
        return {
          model: lastPickedModel,
          provider: lastPickedProvider,
          configs: getDefaultConfigByProvider(
            lastPickedProvider,
            lastPickedModel,
          ),
        };
      }
    }

    // Fall back to the provided defaults
    if (defaultModel && defaultProvider) {
      return {
        model: defaultModel as PROVIDER_MODEL_TYPE | "",
        provider: defaultProvider,
        configs: defaultConfigs ?? getDefaultConfigByProvider(defaultProvider),
      };
    }

    // Last resort: calculate a default model from available providers
    const calculatedModel = calculateDefaultModel(
      lastPickedModel,
      providerKeys,
    ) as PROVIDER_MODEL_TYPE | "";
    const calculatedProvider = calculateModelProvider(calculatedModel);
    return {
      model: calculatedModel,
      provider: calculatedProvider,
      configs: getDefaultConfigByProvider(calculatedProvider, calculatedModel),
    };
  }, [
    lastPickedModel,
    providerKeys,
    calculateModelProvider,
    calculateDefaultModel,
    defaultModel,
    defaultProvider,
    defaultConfigs,
  ]);

  // Handler for when user selects a new model
  const handleModelChange = useCallback(
    (newModel: PROVIDER_MODEL_TYPE) => {
      setLastPickedModel(newModel);
    },
    [setLastPickedModel],
  );

  // Handler for when a new provider is added
  const handleAddProvider = useCallback(
    (addedProvider: COMPOSED_PROVIDER_TYPE) => {
      if (!model) {
        setLastPickedModel(
          calculateDefaultModel(
            model as PROVIDER_MODEL_TYPE | "",
            [addedProvider],
            addedProvider,
          ),
        );
      }
    },
    [calculateDefaultModel, model, setLastPickedModel],
  );

  // Handler for when a provider is deleted
  const handleDeleteProvider = useCallback(
    (deletedProvider: COMPOSED_PROVIDER_TYPE) => {
      const currentProvider = calculateModelProvider(
        model as PROVIDER_MODEL_TYPE | "",
      );
      if (currentProvider === deletedProvider) {
        setLastPickedModel("");
      }
    },
    [calculateModelProvider, model, setLastPickedModel],
  );

  // Pre-built props for PromptModelSelect component
  const modelSelectProps: ModelSelectProps = useMemo(
    () => ({
      value: model as PROVIDER_MODEL_TYPE | "",
      provider,
      onChange: handleModelChange,
      onAddProvider: handleAddProvider,
      onDeleteProvider: handleDeleteProvider,
    }),
    [
      model,
      provider,
      handleModelChange,
      handleAddProvider,
      handleDeleteProvider,
    ],
  );

  return {
    model: model as PROVIDER_MODEL_TYPE | "",
    provider,
    configs,
    modelSelectProps,
  };
};

export default useModelSelection;
