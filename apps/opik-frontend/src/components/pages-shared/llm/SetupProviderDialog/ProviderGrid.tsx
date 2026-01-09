import React from "react";
import { Label } from "@/components/ui/label";
import { Tag } from "@/components/ui/tag";
import { COMPOSED_PROVIDER_TYPE, PROVIDER_TYPE } from "@/types/providers";
import { PROVIDERS } from "@/constants/providers";
import { cn } from "@/lib/utils";

export interface ProviderGridOption {
  value: COMPOSED_PROVIDER_TYPE;
  label: string;
  providerType: PROVIDER_TYPE;
  configuredId?: string;
  isConfigured?: boolean;
  description?: string;
}

interface ProviderGridProps {
  options: ProviderGridOption[];
  selectedProvider: COMPOSED_PROVIDER_TYPE | "";
  onSelectProvider: (
    composedProviderType: COMPOSED_PROVIDER_TYPE,
    providerType: PROVIDER_TYPE,
    configuredId?: string,
  ) => void;
  disabled?: boolean;
}

const ProviderGrid: React.FC<ProviderGridProps> = ({
  options,
  selectedProvider,
  onSelectProvider,
  disabled = false,
}) => {
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
