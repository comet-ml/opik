import React, { useCallback, useMemo } from "react";
import { Plus } from "lucide-react";

import { cn } from "@/lib/utils";
import {
  COMPOSED_PROVIDER_TYPE,
  PROVIDER_TYPE,
  ProviderObject,
} from "@/types/providers";
import { IconType, PROVIDERS, PROVIDERS_OPTIONS } from "@/constants/providers";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectSeparator,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { Tag } from "@/components/ui/tag";
import {
  buildComposedProviderKey,
  getProviderDisplayName,
} from "@/lib/provider";
import { useIsFeatureEnabled } from "@/components/feature-toggles-provider";
import { FeatureToggleKeys } from "@/types/feature-toggles";

export const ADD_CUSTOM_PROVIDER_VALUE = buildComposedProviderKey(
  PROVIDER_TYPE.CUSTOM,
  "__add_custom_provider__",
);

export interface ProviderOption {
  value: COMPOSED_PROVIDER_TYPE;
  label: string;
  icon: IconType;
  description?: string;
  configuredId?: string;
}

type ProviderSelectProps = {
  value: COMPOSED_PROVIDER_TYPE | string;
  onChange: (provider: COMPOSED_PROVIDER_TYPE, id?: string) => void;
  disabled?: boolean;
  configuredProvidersList?: ProviderObject[];
  hasError?: boolean;
};

const ProviderSelect: React.FC<ProviderSelectProps> = ({
  value,
  onChange,
  disabled,
  configuredProvidersList,
  hasError,
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
      // OPIK_FREE is not included - it's handled separately in PromptModelSelect
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

  const options = useMemo(() => {
    const providerOptions: ProviderOption[] = [];

    // Filter standard providers based on feature flags
    const standardProviders = PROVIDERS_OPTIONS.filter((option) => {
      if (
        option.value === PROVIDER_TYPE.CUSTOM ||
        option.value === PROVIDER_TYPE.OPIK_FREE
      ) {
        return false;
      }
      return providerEnabledMap[option.value];
    });

    standardProviders.forEach((option) => {
      const [id] =
        configuredProvidersList
          ?.filter((key) => key.provider === option.value)
          .map((key) => key.id) || [];

      providerOptions.push({
        ...option,
        configuredId: id,
      });
    });

    // Only add custom providers if custom LLM provider is enabled
    if (isCustomLLMEnabled) {
      const customProviders =
        configuredProvidersList?.filter(
          (key) => key.provider === PROVIDER_TYPE.CUSTOM,
        ) || [];

      if (customProviders.length > 0) {
        customProviders.forEach((customProvider) => {
          providerOptions.push({
            value: customProvider.ui_composed_provider,
            label: getProviderDisplayName(customProvider),
            icon: PROVIDERS[PROVIDER_TYPE.CUSTOM].icon,
            configuredId: customProvider.id,
            description: customProvider.base_url,
          });
        });
      }
    }

    return providerOptions;
  }, [configuredProvidersList, providerEnabledMap, isCustomLLMEnabled]);

  const renderTrigger = useCallback(
    (value: string) => {
      if (value === ADD_CUSTOM_PROVIDER_VALUE) {
        const Icon = PROVIDERS[PROVIDER_TYPE.CUSTOM].icon;
        return (
          <div className="flex w-full items-center justify-between text-foreground">
            <div className="flex items-center gap-2">
              <Icon />
              Custom provider
            </div>
          </div>
        );
      }

      const option = options?.find((o) => o.value === value);

      if (!option) {
        return <SelectValue placeholder="Select a provider" />;
      }

      const Icon = option.icon;
      const label = option.label;

      return (
        <div className="flex w-full items-center justify-between">
          <div className="flex items-center gap-2">
            <Icon />
            {label}
          </div>
        </div>
      );
    },
    [options],
  );

  const renderOption = useCallback((option: ProviderOption) => {
    const isConfigured = Boolean(option.configuredId);

    const Icon = option.icon || PROVIDERS[option.value as PROVIDER_TYPE]?.icon;

    return (
      <SelectItem
        key={option.value}
        value={option.value}
        description={
          option.description ? (
            <div className="pl-6 text-xs text-muted-foreground">
              {option.description}
            </div>
          ) : undefined
        }
        withoutCheck
        wrapperAsChild={true}
      >
        <div className="flex w-full items-center justify-between">
          <div className="flex items-center gap-2">
            {Icon && <Icon />}
            {option.label}
          </div>
          {isConfigured && <Tag variant="green">Configured</Tag>}
        </div>
      </SelectItem>
    );
  }, []);

  const handleChange = useCallback(
    (newValue: string) => {
      onChange(
        newValue,
        options.find((o) => o.value === newValue)?.configuredId,
      );
    },
    [onChange, options],
  );

  return (
    <Select value={value} onValueChange={handleChange} disabled={disabled}>
      <SelectTrigger
        className={cn(
          "data-[placeholder]:text-light-slate data-[placeholder]:dark:disabled:text-muted-gray",
          { "border-destructive": hasError },
        )}
      >
        {renderTrigger(value)}
      </SelectTrigger>
      <SelectContent>
        {options.map((option) => renderOption(option))}
        {isCustomLLMEnabled && (
          <>
            <SelectSeparator />
            <SelectItem
              value={ADD_CUSTOM_PROVIDER_VALUE}
              withoutCheck
              wrapperAsChild={true}
            >
              <div className="flex w-full items-center justify-between">
                <div className="flex items-center gap-2">
                  <Plus className="size-4 " />
                  Add custom provider
                </div>
              </div>
            </SelectItem>
          </>
        )}
      </SelectContent>
    </Select>
  );
};

export default ProviderSelect;
