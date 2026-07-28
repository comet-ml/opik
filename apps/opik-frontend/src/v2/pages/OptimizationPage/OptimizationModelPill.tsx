import React, { useMemo } from "react";
import { Sparkles } from "lucide-react";

import useLLMProviderModelsData from "@/hooks/useLLMProviderModelsData";
import { parseComposedProviderType } from "@/lib/provider";
import { PROVIDER_MODEL_TYPE } from "@/types/providers";
import OptimizationConfigPill, {
  CONFIG_PILL_ICON_CLASS,
} from "@/v2/pages-shared/optimizations/OptimizationConfigPill";
import { getModelPillIcon } from "@/v2/pages-shared/optimizations/modelPillIcon";

type OptimizationModelPillProps = {
  model: string;
};

/**
 * Model pill in the run header. Resolves the model's provider to a brand icon
 * (see modelPillIcon), falling back to Sparkles for unknown models.
 */
const OptimizationModelPill: React.FC<OptimizationModelPillProps> = ({
  model,
}) => {
  const { calculateModelProvider } = useLLMProviderModelsData();

  const providerType = useMemo(() => {
    const composed = calculateModelProvider(model as PROVIDER_MODEL_TYPE);
    return composed ? parseComposedProviderType(composed) : undefined;
  }, [calculateModelProvider, model]);

  const Icon = getModelPillIcon(providerType);

  return (
    <OptimizationConfigPill
      icon={
        Icon ? (
          <Icon className={CONFIG_PILL_ICON_CLASS} />
        ) : (
          <Sparkles className={CONFIG_PILL_ICON_CLASS} />
        )
      }
    >
      {model}
    </OptimizationConfigPill>
  );
};

export default OptimizationModelPill;
