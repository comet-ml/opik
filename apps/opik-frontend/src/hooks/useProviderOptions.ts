import { useMemo } from "react";
import { PROVIDER_TYPE, ProviderObject } from "@/types/providers";
import { FeatureToggleKeys } from "@/types/feature-toggles";
import { useIsFeatureEnabled } from "@/components/feature-toggles-provider";
import { PROVIDERS, PROVIDERS_OPTIONS } from "@/constants/providers";
import {
  buildComposedProviderKey,
  getProviderDisplayName,
} from "@/lib/provider";
import { ProviderGridOption } from "@/components/pages-shared/llm/SetupProviderDialog/ProviderGrid";

interface UseProviderOptionsParams {
  configuredProvidersList?: ProviderObject[];
  includeConfiguredStatus?: boolean;
}

/**
 * Hook to build provider options based on feature toggles.
 * Centralizes the feature toggle logic and provider filtering.
 *
 * @param configuredProvidersList - Optional list of already configured providers
 * @param includeConfiguredStatus - Whether to include isConfigured status and configured instances
 * @returns Array of provider options filtered by feature toggles
 */
export function useProviderOptions({
  configuredProvidersList,
  includeConfiguredStatus = false,
}: UseProviderOptionsParams = {}): ProviderGridOption[] {
  // Feature toggle hooks for all providers
  const isOpenAIEnabled = useIsFeatureEnabled(
    FeatureToggleKeys.OPENAI_PROVIDER_ENABLED,
  );
  const isAnthropicEnabled = useIsFeatureEnabled(
    FeatureToggleKeys.ANTHROPIC_PROVIDER_ENABLED,
  );
  const isGeminiEnabled = useIsFeatureEnabled(
    FeatureToggleKeys.GEMINI_PROVIDER_ENABLED,
  );
  const isOpenRouterEnabled = useIsFeatureEnabled(
    FeatureToggleKeys.OPENROUTER_PROVIDER_ENABLED,
  );
  const isVertexAIEnabled = useIsFeatureEnabled(
    FeatureToggleKeys.VERTEXAI_PROVIDER_ENABLED,
  );
  const isBedrockEnabled = useIsFeatureEnabled(
    FeatureToggleKeys.BEDROCK_PROVIDER_ENABLED,
  );
  const isCustomLLMEnabled = useIsFeatureEnabled(
    FeatureToggleKeys.CUSTOMLLM_PROVIDER_ENABLED,
  );

  const providerEnabledMap = useMemo(
    () => ({
      [PROVIDER_TYPE.OPEN_AI]: isOpenAIEnabled,
      [PROVIDER_TYPE.ANTHROPIC]: isAnthropicEnabled,
      [PROVIDER_TYPE.GEMINI]: isGeminiEnabled,
      [PROVIDER_TYPE.OPEN_ROUTER]: isOpenRouterEnabled,
      [PROVIDER_TYPE.VERTEX_AI]: isVertexAIEnabled,
      [PROVIDER_TYPE.BEDROCK]: isBedrockEnabled,
      [PROVIDER_TYPE.CUSTOM]: isCustomLLMEnabled,
    }),
    [
      isOpenAIEnabled,
      isAnthropicEnabled,
      isGeminiEnabled,
      isOpenRouterEnabled,
      isVertexAIEnabled,
      isBedrockEnabled,
      isCustomLLMEnabled,
    ],
  );

  return useMemo(() => {
    const options: ProviderGridOption[] = [];

    // Filter standard providers based on feature flags
    const standardProviders = PROVIDERS_OPTIONS.filter((option) => {
      if (
        option.value === PROVIDER_TYPE.CUSTOM ||
        option.value === PROVIDER_TYPE.OPIK_FREE ||
        option.value === PROVIDER_TYPE.BEDROCK
      ) {
        return false;
      }
      return providerEnabledMap[option.value];
    });

    standardProviders.forEach((option) => {
      const configuredProvider = includeConfiguredStatus
        ? configuredProvidersList?.find((key) => key.provider === option.value)
        : undefined;

      options.push({
        value: buildComposedProviderKey(option.value),
        label: option.label,
        providerType: option.value,
        configuredId: configuredProvider?.id,
        isConfigured: Boolean(configuredProvider),
      });
    });

    // Add Bedrock provider option for creating new instances
    if (isBedrockEnabled) {
      options.push({
        value: buildComposedProviderKey(
          PROVIDER_TYPE.BEDROCK,
          "__add_bedrock_provider__",
        ),
        label: PROVIDERS[PROVIDER_TYPE.BEDROCK].label,
        providerType: PROVIDER_TYPE.BEDROCK,
      });

      // Add configured Bedrock instances if requested
      if (includeConfiguredStatus) {
        const bedrockProviders =
          configuredProvidersList?.filter(
            (key) => key.provider === PROVIDER_TYPE.BEDROCK,
          ) || [];

        bedrockProviders.forEach((bedrockProvider) => {
          options.push({
            value: bedrockProvider.ui_composed_provider,
            label: getProviderDisplayName(bedrockProvider),
            providerType: PROVIDER_TYPE.BEDROCK,
            configuredId: bedrockProvider.id,
            isConfigured: true,
          });
        });
      }
    }

    // Add custom provider option for creating new instances
    if (isCustomLLMEnabled) {
      options.push({
        value: buildComposedProviderKey(
          PROVIDER_TYPE.CUSTOM,
          "__add_custom_provider__",
        ),
        label: PROVIDERS[PROVIDER_TYPE.CUSTOM].label,
        providerType: PROVIDER_TYPE.CUSTOM,
      });

      // Add configured custom provider instances if requested
      if (includeConfiguredStatus) {
        const customProviders =
          configuredProvidersList?.filter(
            (key) => key.provider === PROVIDER_TYPE.CUSTOM,
          ) || [];

        customProviders.forEach((customProvider) => {
          options.push({
            value: customProvider.ui_composed_provider,
            label: getProviderDisplayName(customProvider),
            providerType: PROVIDER_TYPE.CUSTOM,
            configuredId: customProvider.id,
            isConfigured: true,
          });
        });
      }
    }

    return options;
  }, [
    configuredProvidersList,
    providerEnabledMap,
    isCustomLLMEnabled,
    isBedrockEnabled,
    includeConfiguredStatus,
  ]);
}

