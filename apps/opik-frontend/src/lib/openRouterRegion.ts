import { OpenRouterRegion, UnavailableDecisionModel } from "@/types/providers";

const OPENROUTER_REGION_NAMES: Partial<Record<OpenRouterRegion, string>> = {
  global: "Global",
  eu: "European Union",
  us: "United States",
};

const REGIONAL_OPENROUTER_REGIONS: Partial<
  Record<OpenRouterRegion, { shortName: string }>
> = {
  eu: { shortName: "EU" },
  us: { shortName: "US" },
};

export const getOpenRouterRegionName = (region?: OpenRouterRegion) =>
  region ? OPENROUTER_REGION_NAMES[region] : undefined;

export const getDecisionModelUnavailableReason = (
  model: string,
  unavailableDecisionModels?: UnavailableDecisionModel[],
): string | undefined => {
  const unavailable = unavailableDecisionModels?.find(
    (decisionModel) => decisionModel.model === model,
  );
  const regional =
    unavailable && REGIONAL_OPENROUTER_REGIONS[unavailable.region];

  return regional
    ? `Not available in OpenRouter's ${regional.shortName} region`
    : undefined;
};
