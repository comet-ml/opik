import React, { useCallback, useMemo } from "react";
import { Plus } from "lucide-react";

import { cn } from "@/lib/utils";
import { PROVIDER_TYPE, ProviderKey, ProviderOption } from "@/types/providers";
import SelectBox from "@/components/shared/SelectBox/SelectBox";
import { PROVIDERS, PROVIDERS_OPTIONS } from "@/constants/providers";
import {
  SelectItem,
  SelectValue,
  SelectSeparator,
} from "@/components/ui/select";
import { Tag } from "@/components/ui/tag";

const ADD_CUSTOM_PROVIDER_VALUE = "__add_custom_provider__" as const;

type ProviderSelectProps = {
  value: PROVIDER_TYPE | string | "";
  onChange: (provider: PROVIDER_TYPE | string) => void;
  disabled?: boolean;
  configuredProviderKeys?: PROVIDER_TYPE[]; // Deprecated: Use configuredProvidersList instead
  configuredProvidersList?: ProviderKey[];
  hasError?: boolean;
  onAddCustomProvider?: () => void;
  isAddingCustomProvider?: boolean;
};

// Helper function to get display name for custom providers
const getCustomProviderDisplayName = (provider: ProviderKey): string => {
  return provider.provider_name || provider.keyName || "Custom provider";
};

const ProviderSelect: React.FC<ProviderSelectProps> = ({
  value,
  onChange,
  disabled,
  configuredProviderKeys,
  configuredProvidersList,
  hasError,
  onAddCustomProvider,
  isAddingCustomProvider,
}) => {
  const options = useMemo(() => {
    const providerOptions: ProviderOption[] = [];

    // Add standard providers (non-custom)
    const standardProviders = PROVIDERS_OPTIONS.filter(
      (option) => option.value !== PROVIDER_TYPE.CUSTOM,
    );

    standardProviders.forEach((option) => {
      const isConfigured =
        configuredProviderKeys?.includes(option.value as PROVIDER_TYPE) ||
        configuredProvidersList?.some((key) => key.provider === option.value) ||
        false;

      providerOptions.push({
        ...option,
        configured: isConfigured,
      });
    });

    // Add each configured custom provider as a separate option
    const customProviders =
      configuredProvidersList?.filter(
        (key) => key.provider === PROVIDER_TYPE.CUSTOM,
      ) || [];

    if (customProviders.length > 0) {
      customProviders.forEach((customProvider) => {
        providerOptions.push({
          value: customProvider.id,
          label: getCustomProviderDisplayName(customProvider),
          icon: PROVIDERS[PROVIDER_TYPE.CUSTOM].icon,
          configured: true,
          isCustomProvider: true,
          description: customProvider.base_url,
        });
      });
    }

    // Add the "Add custom provider" option at the end
    if (onAddCustomProvider) {
      // Always add separator before "Add custom provider"
      providerOptions.push({
        isSeparator: true,
        value: "__separator_add_custom__",
      });

      providerOptions.push({
        value: ADD_CUSTOM_PROVIDER_VALUE,
        label: "Add custom provider",
        icon: Plus,
        isAddCustom: true,
      });
    }

    return providerOptions;
  }, [configuredProviderKeys, configuredProvidersList, onAddCustomProvider]);

  const renderTrigger = useCallback(
    (value: string) => {
      // When adding a new custom provider, show "Custom provider" even if value is empty
      if (isAddingCustomProvider && !value) {
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

      if (!value) {
        return <SelectValue placeholder="Select a provider" />;
      }

      // Check if it's a custom provider ID
      const customProvider = configuredProvidersList?.find(
        (key) => key.id === value,
      );

      if (customProvider) {
        const Icon = PROVIDERS[PROVIDER_TYPE.CUSTOM].icon;
        return (
          <div className="flex w-full items-center justify-between text-foreground">
            <div className="flex items-center gap-2">
              <Icon />
              {getCustomProviderDisplayName(customProvider)}
            </div>
          </div>
        );
      }

      // Standard provider
      const Icon = PROVIDERS[value as PROVIDER_TYPE]?.icon;
      const label = PROVIDERS[value as PROVIDER_TYPE]?.label;

      // If Icon or label is undefined, it means the provider was deleted or is invalid
      // Fall back to placeholder
      if (!Icon || !label) {
        return <SelectValue placeholder="Select a provider" />;
      }

      return (
        <div className="flex w-full items-center justify-between text-foreground">
          <div className="flex items-center gap-2">
            <Icon />
            {label}
          </div>
        </div>
      );
    },
    [configuredProvidersList, isAddingCustomProvider],
  );

  const renderOption = useCallback((option: ProviderOption) => {
    // Handle separator
    if (option.isSeparator) {
      return <SelectSeparator key={option.value} />;
    }

    const isAddCustom = option.isAddCustom || false;
    const isConfigured = option.configured || false;

    // Use the icon from the option if it exists, otherwise look it up
    const Icon = option.icon || PROVIDERS[option.value as PROVIDER_TYPE]?.icon;

    return (
      <SelectItem
        key={option.value}
        value={option.value}
        description={
          !isAddCustom && option.description ? (
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
            {Icon && <Icon className={cn(isAddCustom && "size-4")} />}
            {option.label}
          </div>
          {isConfigured && <Tag variant="green">Configured</Tag>}
        </div>
      </SelectItem>
    );
  }, []);

  const handleChange = useCallback(
    (newValue: string) => {
      if (newValue === ADD_CUSTOM_PROVIDER_VALUE) {
        onAddCustomProvider?.();
        return;
      }
      onChange(newValue);
    },
    [onChange, onAddCustomProvider],
  );

  return (
    <SelectBox
      disabled={disabled}
      renderTrigger={renderTrigger}
      renderOption={renderOption}
      value={value}
      onChange={handleChange}
      options={options as unknown as { value: string; label: string }[]}
      className={cn({
        "border-destructive": hasError,
      })}
    />
  );
};

export default ProviderSelect;
