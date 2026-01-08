import React, { useCallback } from "react";
import { Plus } from "lucide-react";

import { cn } from "@/lib/utils";
import {
  COMPOSED_PROVIDER_TYPE,
  PROVIDER_TYPE,
  ProviderObject,
} from "@/types/providers";
import { PROVIDERS } from "@/constants/providers";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectSeparator,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { Tag } from "@/components/ui/tag";
import { buildComposedProviderKey } from "@/lib/provider";
import {
  useProviderOptions,
  useProviderEnabledMap,
} from "@/hooks/useProviderOptions";
import { ProviderGridOption } from "@/components/pages-shared/llm/SetupProviderDialog/ProviderGrid";

export const ADD_CUSTOM_PROVIDER_VALUE = buildComposedProviderKey(
  PROVIDER_TYPE.CUSTOM,
  "__add_custom_provider__",
);

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
  // Use shared hooks for provider options and enabled map
  const options = useProviderOptions({ configuredProvidersList });
  const providerEnabledMap = useProviderEnabledMap();
  const isCustomLLMEnabled = providerEnabledMap[PROVIDER_TYPE.CUSTOM];

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

      const Icon = PROVIDERS[option.providerType]?.icon;
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

  const renderOption = useCallback((option: ProviderGridOption) => {
    const isConfigured = Boolean(option.configuredId);

    const Icon = PROVIDERS[option.providerType]?.icon;

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
