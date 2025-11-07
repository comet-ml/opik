import React from "react";
import { Label } from "@/components/ui/label";
import { PROVIDER_TYPE } from "@/types/providers";
import { PROVIDERS_OPTIONS } from "@/constants/providers";
import { cn } from "@/lib/utils";

interface ProviderGridProps {
  selectedProvider: PROVIDER_TYPE | "";
  onSelectProvider: (provider: PROVIDER_TYPE) => void;
}

const ProviderGrid: React.FC<ProviderGridProps> = ({
  selectedProvider,
  onSelectProvider,
}) => {
  return (
    <div className="flex flex-col gap-2">
      <Label>Provider</Label>
      <div className="grid grid-cols-3 gap-3">
        {PROVIDERS_OPTIONS.map((provider) => {
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
