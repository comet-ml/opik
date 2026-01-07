import React, { useMemo } from "react";
import { Label } from "@/components/ui/label";
import { Tag } from "@/components/ui/tag";
import {
  COMPOSED_PROVIDER_TYPE,
  PROVIDER_TYPE,
  ProviderObject,
} from "@/types/providers";
import { PROVIDERS, PROVIDERS_OPTIONS } from "@/constants/providers";
import { cn } from "@/lib/utils";
import { useIsFeatureEnabled } from "@/components/feature-toggles-provider";
import { FeatureToggleKeys } from "@/types/feature-toggles";
import {
  buildComposedProviderKey,
  getProviderDisplayName,
} from "@/lib/provider";

export interface ProviderGridOption {
  value: COMPOSED_PROVIDER_TYPE;
  label: string;
  providerType: PROVIDER_TYPE;
  configuredId?: string;
  isConfigured?: boolean;
}

interface ProviderGridProps {
  selectedProvider: COMPOSED_PROVIDER_TYPE | "";
  onSelectProvider: (
    composedProviderType: COMPOSED_PROVIDER_TYPE,
    providerType: PROVIDER_TYPE,
    configuredId?: string,
  ) => void;
  configuredProvidersList?: ProviderObject[];
  disabled?: boolean;
}

const ProviderGrid: React.FC<ProviderGridProps> = ({
  selectedProvider,
  onSelectProvider,
  configuredProvidersList,
  disabled = false,
}) => {
  // Get feature flags for all providers
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

  // Build provider options including configured providers
  const options = useMemo(() => {
    const providerOptions: ProviderGridOption[] = [];

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
      const configuredProvider = configuredProvidersList?.find(
        (key) => key.provider === option.value,
      );

      providerOptions.push({
        value: buildComposedProviderKey(option.value),
        label: option.label,
        providerType: option.value,
        configuredId: configuredProvider?.id,
        isConfigured: Boolean(configuredProvider),
      });
    });

    // Add Bedrock provider option for creating new instances
    if (isBedrockEnabled) {
      providerOptions.push({
        value: buildComposedProviderKey(
          PROVIDER_TYPE.BEDROCK,
          "__add_bedrock_provider__",
        ),
        label: PROVIDERS[PROVIDER_TYPE.BEDROCK].label,
        providerType: PROVIDER_TYPE.BEDROCK,
      });

      // Add configured Bedrock instances
      const bedrockProviders =
        configuredProvidersList?.filter(
          (key) => key.provider === PROVIDER_TYPE.BEDROCK,
        ) || [];

      bedrockProviders.forEach((bedrockProvider) => {
        providerOptions.push({
          value: bedrockProvider.ui_composed_provider,
          label: getProviderDisplayName(bedrockProvider),
          providerType: PROVIDER_TYPE.BEDROCK,
          configuredId: bedrockProvider.id,
          isConfigured: true,
        });
      });
    }

    // Add custom provider option for creating new instances
    if (isCustomLLMEnabled) {
      providerOptions.push({
        value: buildComposedProviderKey(
          PROVIDER_TYPE.CUSTOM,
          "__add_custom_provider__",
        ),
        label: PROVIDERS[PROVIDER_TYPE.CUSTOM].label,
        providerType: PROVIDER_TYPE.CUSTOM,
      });

      // Add configured custom provider instances
      const customProviders =
        configuredProvidersList?.filter(
          (key) => key.provider === PROVIDER_TYPE.CUSTOM,
        ) || [];

      customProviders.forEach((customProvider) => {
        providerOptions.push({
          value: customProvider.ui_composed_provider,
          label: getProviderDisplayName(customProvider),
          providerType: PROVIDER_TYPE.CUSTOM,
          configuredId: customProvider.id,
          isConfigured: true,
        });
      });
    }

    return providerOptions;
  }, [
    configuredProvidersList,
    providerEnabledMap,
    isCustomLLMEnabled,
    isBedrockEnabled,
  ]);

  return (
    <div className="flex flex-col gap-2">
      <Label>Provider</Label>
      <div className="grid grid-cols-2 gap-3">
        {options.map((option) => {
          const Icon = PROVIDERS[option.providerType]?.icon;
          const isSelected = selectedProvider === option.value;

          return (
            <button
              key={option.value}
              type="button"
              disabled={disabled}
              onClick={() =>
                onSelectProvider(
                  option.value,
                  option.providerType,
                  option.configuredId,
                )
              }
              className={cn(
                "relative flex items-center gap-2 rounded-lg border bg-background p-4 transition-all duration-200 hover:bg-primary-foreground cursor-pointer text-left",
                isSelected
                  ? "border-primary bg-primary-foreground"
                  : "border-border",
                disabled && "opacity-50 cursor-not-allowed",
              )}
            >
              <div className="flex min-w-10 items-center justify-center">
                {Icon && <Icon className="size-8 text-foreground" />}
              </div>
              <div className="min-w-0 flex-1">
                <div className="flex items-center gap-2">
                  <h3 className="comet-body-s-accented truncate text-foreground">
                    {option.label}
                  </h3>
                  {option.isConfigured && (
                    <Tag
                      variant="green"
                      size="sm"
                      className="h-5 shrink-0 leading-5"
                    >
                      Configured
                    </Tag>
                  )}
                </div>
              </div>
            </button>
          );
        })}
      </div>
    </div>
  );
};

export default ProviderGrid;
