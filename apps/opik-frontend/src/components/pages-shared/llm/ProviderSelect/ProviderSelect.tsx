import React, { useCallback, useMemo } from "react";

import get from "lodash/get";

import { cn } from "@/lib/utils";
import { PROVIDER_TYPE } from "@/types/providers";
import { DropdownOption } from "@/types/shared";
import SelectBox from "@/components/shared/SelectBox/SelectBox";
import { PROVIDERS, PROVIDERS_OPTIONS } from "@/constants/providers";
import { SelectItem, SelectValue } from "@/components/ui/select";
import { Tag } from "@/components/ui/tag";

type ProviderSelectProps = {
  value: PROVIDER_TYPE | "";
  onChange: (provider: PROVIDER_TYPE) => void;
  disabled?: boolean;
  configuredProviderKeys?: PROVIDER_TYPE[];
  hasError?: boolean;
};

const ProviderSelect: React.FC<ProviderSelectProps> = ({
  value,
  onChange,
  disabled,
  configuredProviderKeys,
  hasError,
}) => {
  const options = useMemo(() => {
    return PROVIDERS_OPTIONS.map((option) => ({
      ...option,
      configured: configuredProviderKeys?.includes(
        option.value as PROVIDER_TYPE,
      ),
    }));
  }, [configuredProviderKeys]);

  const renderTrigger = useCallback((value: string) => {
    if (!value) {
      return <SelectValue placeholder="Select a provider" />;
    }
    const Icon = PROVIDERS[value as PROVIDER_TYPE]?.icon;
    const label = PROVIDERS[value as PROVIDER_TYPE]?.label;

    return (
      <div className="flex w-full items-center justify-between">
        <div className="flex items-center gap-2">
          <Icon />
          {label}
        </div>
      </div>
    );
  }, []);

  const renderOption = useCallback((option: DropdownOption<PROVIDER_TYPE>) => {
    const Icon = PROVIDERS[option.value]?.icon;
    const isConfigured = get(option, "configured", false);

    return (
      <SelectItem
        key={option.value}
        value={option.value}
        description={<div className="pl-6">{option.description}</div>}
        withoutCheck
        Wrapper={null}
      >
        <div className="flex w-full items-center justify-between">
          <div className="flex items-center gap-2">
            <Icon />
            {option.label}
          </div>
          {isConfigured && <Tag variant="green">Configured</Tag>}
        </div>
      </SelectItem>
    );
  }, []);

  return (
    <SelectBox
      disabled={disabled}
      renderTrigger={renderTrigger}
      renderOption={renderOption}
      value={value}
      onChange={onChange}
      options={options}
      className={cn({
        "border-destructive": hasError,
      })}
    />
  );
};

export default ProviderSelect;
