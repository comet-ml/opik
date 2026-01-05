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
  persistenceKey: string;
  defaultModel?: string;
  defaultProvider?: COMPOSED_PROVIDER_TYPE | "";
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
  model: PROVIDER_MODEL_TYPE | "";
  provider: COMPOSED_PROVIDER_TYPE | "";
  configs: LLMPromptConfigsType;
  modelSelectProps: ModelSelectProps;
}

const useModelSelection = ({
  persistenceKey,
  defaultModel,
  defaultProvider,
  defaultConfigs,
}: UseModelSelectionParams): UseModelSelectionResult => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);

  const [lastPickedModel, setLastPickedModel] = useLastPickedModel({
    key: persistenceKey,
  });

  const { data: providerKeysData } = useProviderKeys({
    workspaceName,
  });

  const providerKeys = useMemo(() => {
    return providerKeysData?.content?.map((c) => c.ui_composed_provider) || [];
  }, [providerKeysData]);

  const { calculateModelProvider, calculateDefaultModel } =
    useLLMProviderModelsData();

  const { model, provider, configs } = useMemo(() => {
    if (lastPickedModel) {
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

    if (defaultModel && defaultProvider) {
      return {
        model: defaultModel as PROVIDER_MODEL_TYPE | "",
        provider: defaultProvider,
        configs: defaultConfigs ?? getDefaultConfigByProvider(defaultProvider),
      };
    }

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

  const handleModelChange = useCallback(
    (newModel: PROVIDER_MODEL_TYPE) => {
      setLastPickedModel(newModel);
    },
    [setLastPickedModel],
  );

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
