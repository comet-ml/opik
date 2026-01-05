import React, { useMemo } from "react";
import { Label } from "@/components/ui/label";
import { PROVIDER_TYPE } from "@/types/providers";
import { PROVIDERS_OPTIONS } from "@/constants/providers";
import { cn } from "@/lib/utils";
import { useIsFeatureEnabled } from "@/components/feature-toggles-provider";
import { FeatureToggleKeys } from "@/types/feature-toggles";

interface ProviderGridProps {
  selectedProvider: PROVIDER_TYPE | "";
  onSelectProvider: (provider: PROVIDER_TYPE) => void;
}

const ProviderGrid: React.FC<ProviderGridProps> = ({
  selectedProvider,
  onSelectProvider,
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
      [PROVIDER_TYPE.CUSTOM]: isCustomLLMEnabled,
      // OPIK_FREE is not included - it's readOnly and handled separately
    }),
    [
      isOpenAIEnabled,
      isAnthropicEnabled,
      isGeminiEnabled,
      isOpenRouterEnabled,
      isVertexAIEnabled,
      isCustomLLMEnabled,
    ],
  );

  // Filter out OPIK_FREE (handled separately) and disabled providers based on feature flags
  const configurableProviders = useMemo(
    () =>
      PROVIDERS_OPTIONS.filter((provider) => {
        if (provider.value === PROVIDER_TYPE.OPIK_FREE) {
          return false;
        }
        return providerEnabledMap[provider.value];
      }),
    [providerEnabledMap],
  );

  return (
    <div className="flex flex-col gap-2">
      <Label>Provider</Label>
      <div className="grid grid-cols-3 gap-3">
        {configurableProviders.map((provider) => {
          const Icon = provider.icon;
          const isSelected = selectedProvider === provider.value;

          return (
            <button
              key={provider.value}
              type="button"
              onClick={() => onSelectProvider(provider.value)}
              className={cn(
                "flex flex-col items-center justify-center gap-2 rounded-md border p-4 transition-all hover:border-primary hover:bg-muted",
                isSelected ? "border-primary bg-muted" : "border-border",
              )}
            >
              <Icon className="size-8 text-foreground" />
              <span className="comet-body-s text-center">{provider.label}</span>
            </button>
          );
        })}
      </div>
    </div>
  );
};

export default ProviderGrid;
