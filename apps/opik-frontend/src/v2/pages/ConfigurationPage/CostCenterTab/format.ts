import { CatalogType } from "@/lib/model-prices";
import { TagProps } from "@/ui/tag";
import { PROVIDERS } from "@/constants/providers";
import { PROVIDER_TYPE } from "@/types/providers";

export { PRICE_UNIT_SUFFIX, formatPrice } from "@/shared/CostBreakdown/format";

// LiteLLM splits some providers into several names (vertex_ai-language-models,
// bedrock_converse, ollama_chat); each family has one logo.
const PROVIDER_FAMILIES: [string, PROVIDER_TYPE][] = [
  ["vertex_ai", PROVIDER_TYPE.VERTEX_AI],
  ["bedrock", PROVIDER_TYPE.BEDROCK],
  ["ollama", PROVIDER_TYPE.OLLAMA],
];

export const providerIconOf = (provider: string) => {
  const family = PROVIDER_FAMILIES.find(([prefix]) =>
    provider.startsWith(prefix),
  );
  return PROVIDERS[(family ? family[1] : provider) as PROVIDER_TYPE]?.icon;
};

export const TYPE_TAG_VARIANT: Record<CatalogType, TagProps["variant"]> = {
  default: "gray",
  override: "yellow",
  custom: "green",
  alias: "blue",
};

export const TYPE_DESCRIPTIONS: Record<CatalogType, string> = {
  default: "Built-in price from Opik",
  override: "Custom price for an existing model",
  custom: "Custom model added by your organization",
  alias: "Uses another model's price",
};
