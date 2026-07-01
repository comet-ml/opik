import React, { useMemo } from "react";
import { Sparkles } from "lucide-react";

import useLLMProviderModelsData from "@/hooks/useLLMProviderModelsData";
import { parseComposedProviderType } from "@/lib/provider";
import { PROVIDERS } from "@/constants/providers";
import { PROVIDER_MODEL_TYPE, PROVIDER_TYPE } from "@/types/providers";
import ClaudeIcon from "@/icons/integrations/claude.svg?react";
import OptimizationConfigPill, {
  CONFIG_PILL_ICON_CLASS,
} from "./OptimizationConfigPill";

type OptimizationModelPillProps = {
  model: string;
};

/**
 * Model pill in the run header. Resolves the model's provider to pick a brand
 * icon: Anthropic models get the coloured Claude logo (Figma); other providers
 * fall back to their (monochrome) provider mark, and unknown models to Sparkles.
 */
const OptimizationModelPill: React.FC<OptimizationModelPillProps> = ({
  model,
}) => {
  const { calculateModelProvider } = useLLMProviderModelsData();

  const providerType = useMemo(() => {
    const composed = calculateModelProvider(model as PROVIDER_MODEL_TYPE);
    return composed ? parseComposedProviderType(composed) : undefined;
  }, [calculateModelProvider, model]);

  const renderIcon = () => {
    if (providerType === PROVIDER_TYPE.ANTHROPIC) {
      return <ClaudeIcon className="size-3 shrink-0" />;
    }
    const ProviderIcon = providerType && PROVIDERS[providerType]?.icon;
    if (ProviderIcon) {
      return <ProviderIcon className="size-3 shrink-0 text-muted-slate" />;
    }
    return <Sparkles className={CONFIG_PILL_ICON_CLASS} />;
  };

  return (
    <OptimizationConfigPill icon={renderIcon()}>{model}</OptimizationConfigPill>
  );
};

export default OptimizationModelPill;
