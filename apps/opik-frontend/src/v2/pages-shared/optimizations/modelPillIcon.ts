import ClaudeIcon from "@/icons/integrations/claude.svg?react";
import { IconType, PROVIDERS } from "@/constants/providers";
import { PROVIDER_TYPE } from "@/types/providers";

/**
 * Brand-icon overrides for model pills. Most providers use the monochrome
 * provider mark from PROVIDERS, but some show their coloured brand logo —
 * e.g. Anthropic models render the Claude logo rather than the generic
 * Anthropic mark. Add a provider here to override its pill icon.
 */
const MODEL_PILL_ICON_OVERRIDES: Partial<Record<PROVIDER_TYPE, IconType>> = {
  [PROVIDER_TYPE.ANTHROPIC]: ClaudeIcon,
};

/**
 * Icon for a model's provider in a pill: the brand override if one exists,
 * otherwise the provider's own mark. Returns undefined for an unknown provider
 * so callers can fall back to a default icon.
 */
export const getModelPillIcon = (
  providerType?: PROVIDER_TYPE,
): IconType | undefined => {
  if (!providerType) return undefined;
  return (
    MODEL_PILL_ICON_OVERRIDES[providerType] ?? PROVIDERS[providerType]?.icon
  );
};
