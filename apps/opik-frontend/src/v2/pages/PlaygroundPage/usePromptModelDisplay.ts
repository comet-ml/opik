import { useMemo } from "react";
import { COMPOSED_PROVIDER_TYPE } from "@/types/providers";
import { PROVIDERS } from "@/constants/providers";
import { parseComposedProviderType } from "@/lib/provider";
import useLLMProviderModelsData from "@/hooks/useLLMProviderModelsData";

const usePromptModelDisplay = (
  provider: string | null | undefined,
  model: string | null | undefined,
) => {
  const { getProviderModels } = useLLMProviderModelsData();

  const ProviderIcon = useMemo(() => {
    if (!provider) return null;
    const providerType = parseComposedProviderType(
      provider as COMPOSED_PROVIDER_TYPE,
    );
    return PROVIDERS[providerType]?.icon ?? null;
  }, [provider]);

  const modelLabel = useMemo(() => {
    if (!model || !provider) return model ?? null;
    const models = getProviderModels()[provider as COMPOSED_PROVIDER_TYPE];
    return models?.find((m) => m.value === model)?.label ?? model;
  }, [model, provider, getProviderModels]);

  return { ProviderIcon, modelLabel };
};

export default usePromptModelDisplay;
