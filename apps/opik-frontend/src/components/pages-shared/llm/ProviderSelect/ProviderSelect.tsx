import React, { useCallback, useMemo } from "react";
import { Plus } from "lucide-react";

import get from "lodash/get";

import { cn } from "@/lib/utils";
import { PROVIDER_TYPE } from "@/types/providers";
import { DropdownOption } from "@/types/shared";
import SelectBox from "@/components/shared/SelectBox/SelectBox";
import { PROVIDERS, PROVIDERS_OPTIONS } from "@/constants/providers";
import { SelectItem, SelectValue, SelectSeparator } from "@/components/ui/select";
import { Tag } from "@/components/ui/tag";

const ADD_CUSTOM_PROVIDER_VALUE = "__add_custom_provider__" as const;

type ProviderSelectProps = {
  value: PROVIDER_TYPE | "";
  onChange: (provider: PROVIDER_TYPE) => void;
  disabled?: boolean;
  configuredProviderKeys?: PROVIDER_TYPE[];
  hasError?: boolean;
  onAddCustomProvider?: () => void;
};

const ProviderSelect: React.FC<ProviderSelectProps> = ({
  value,
  onChange,
  disabled,
  configuredProviderKeys,
  hasError,
  onAddCustomProvider,
}) => {
  const options = useMemo(() => {
    const providerOptions = PROVIDERS_OPTIONS.map((option) => ({
      ...option,
      configured: configuredProviderKeys?.includes(
        option.value as PROVIDER_TYPE,
      ),
    }));

    // Add the "Add Custom Provider" option at the end
    if (onAddCustomProvider) {
      providerOptions.push({
        value: ADD_CUSTOM_PROVIDER_VALUE as any,
        label: "Add Custom Provider",
        icon: Plus as any,
        apiKeyName: "",
        defaultModel: "" as any,
        isAddCustom: true,
      } as any);
    }

    return providerOptions;
  }, [configuredProviderKeys, onAddCustomProvider]);

  const renderTrigger = useCallback((value: string) => {
    if (!value) {
      return <SelectValue placeholder="Select a provider" />;
    }
    const Icon = PROVIDERS[value as PROVIDER_TYPE]?.icon;
    const label = PROVIDERS[value as PROVIDER_TYPE]?.label;

    return (
      <div className="flex w-full items-center justify-between">
        <div className="flex items-center gap-2">
          <Icon className="text-foreground" />
          {label}
        </div>
      </div>
    );
  }, []);

  const renderOption = useCallback((option: DropdownOption<PROVIDER_TYPE>, index: number, array: DropdownOption<PROVIDER_TYPE>[]) => {
    const isAddCustom = get(option, "isAddCustom", false);
    const Icon = isAddCustom ? Plus : PROVIDERS[option.value]?.icon;
    const isConfigured = get(option, "configured", false);
    const isLastBeforeCustom = !isAddCustom && index === array.length - 2 && get(array[array.length - 1], "isAddCustom", false);

    return (
      <React.Fragment key={option.value}>
        {isLastBeforeCustom && <SelectSeparator key={`sep-${option.value}`} />}
        <SelectItem
          key={option.value}
          value={option.value}
          description={!isAddCustom && <div className="pl-6">{option.description}</div>}
          withoutCheck
          wrapperAsChild={true}
          className={cn(isAddCustom && "text-primary font-medium")}
        >
          <div className="flex w-full items-center justify-between">
            <div className="flex items-center gap-2">
              {Icon && <Icon className={cn(isAddCustom && "size-4")} />}
              {option.label}
            </div>
            {isConfigured && <Tag variant="green">Configured</Tag>}
          </div>
        </SelectItem>
      </React.Fragment>
    );
  }, []);

  const handleChange = useCallback(
    (newValue: PROVIDER_TYPE) => {
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
      renderOption={(option, index) => renderOption(option, index, options)}
      value={value}
      onChange={handleChange}
      options={options}
      className={cn({
        "border-destructive": hasError,
      })}
    />
  );
};

export default ProviderSelect;
