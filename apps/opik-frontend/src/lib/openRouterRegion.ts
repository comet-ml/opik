import { OpenRouterRegion } from "@/types/providers";
import {
  DECISION_MODELS,
  DECISION_MODELS_OPENROUTER_REGIONS,
} from "@/constants/decisionModels";

const REGIONAL_OPENROUTER_REGIONS: Partial<
  Record<OpenRouterRegion, { name: string; shortName: string }>
> = {
  eu: { name: "European Union", shortName: "EU" },
  us: { name: "United States", shortName: "US" },
};

export const getOpenRouterRegionName = (region?: OpenRouterRegion) =>
  region ? REGIONAL_OPENROUTER_REGIONS[region]?.name : undefined;

export const getDecisionModelUnavailableReason = (
  model: string,
  region?: OpenRouterRegion,
): string | undefined => {
  if (!region) return undefined;

  const regional = REGIONAL_OPENROUTER_REGIONS[region];
  const isOpenRouterDecisionModel = DECISION_MODELS.some(
    (decisionModel) => decisionModel.value === model,
  );
  if (
    !regional ||
    !isOpenRouterDecisionModel ||
    DECISION_MODELS_OPENROUTER_REGIONS.includes(region)
  ) {
    return undefined;
  }

  return `Not available in OpenRouter's ${regional.shortName} region`;
};
