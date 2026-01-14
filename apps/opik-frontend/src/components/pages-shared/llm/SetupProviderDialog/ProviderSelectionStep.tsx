import React from "react";
import { COMPOSED_PROVIDER_TYPE, PROVIDER_TYPE } from "@/types/providers";
import ProviderGrid, { ProviderGridOption } from "./ProviderGrid";

interface ProviderSelectionStepProps {
  providerOptions: ProviderGridOption[];
  selectedComposedProvider: COMPOSED_PROVIDER_TYPE | "";
  onSelectProvider: (
    composedProviderType: COMPOSED_PROVIDER_TYPE,
    providerType: PROVIDER_TYPE,
  ) => void;
}

const ProviderSelectionStep: React.FC<ProviderSelectionStepProps> = ({
  providerOptions,
  selectedComposedProvider,
  onSelectProvider,
}) => {
  if (providerOptions.length === 0) {
    return (
      <div className="comet-body-s text-muted-foreground">
        No providers available for this environment
      </div>
    );
  }

  return (
    <div className="flex flex-col gap-4">
      <ProviderGrid
        options={providerOptions}
        selectedProvider={selectedComposedProvider}
        onSelectProvider={onSelectProvider}
      />
    </div>
  );
};

export default ProviderSelectionStep;
