import modelPricing from "@/data/model_prices_and_context_window.json";

type ModelPricingEntry = {
  supports_vision?: boolean;
  [key: string]: unknown;
};

const normalizeModelName = (value: string) => value.trim().toLowerCase();

const modelEntries = modelPricing as Record<string, ModelPricingEntry>;

const VISION_CAPABILITIES = new Map<string, boolean>();
const NORMALIZED_VISION_CAPABILITIES = new Map<string, boolean>();

// Initialize vision capabilities from model pricing data
Object.entries(modelEntries).forEach(([modelName, entry]) => {
  if (!modelName) {
    return;
  }

  const supportsVision = Boolean(entry?.supports_vision);
  VISION_CAPABILITIES.set(modelName, supportsVision);
  NORMALIZED_VISION_CAPABILITIES.set(
    normalizeModelName(modelName),
    supportsVision,
  );
});

/**
 * Generates candidate keys for model name lookup.
 * Handles various model name formats like:
 * - provider/model:version
 * - provider/model
 * - model:version
 * - model
 */
const candidateKeys = (modelName: string): string[] => {
  const normalized = normalizeModelName(modelName);
  const candidates = new Set<string>([normalized]);

  // Extract model name after slash (e.g., "openai/gpt-4" -> "gpt-4")
  const slashIndex = normalized.lastIndexOf("/") + 1;
  if (slashIndex > 0 && slashIndex < normalized.length) {
    candidates.add(normalized.slice(slashIndex));
  }

  // Extract model name before colon (e.g., "gpt-4:2024" -> "gpt-4")
  const colonIndex = normalized.indexOf(":");
  if (colonIndex > 0) {
    candidates.add(normalized.slice(0, colonIndex));

    // Combine slash and colon extraction (e.g., "openai/gpt-4:2024" -> "gpt-4")
    if (slashIndex > 0 && slashIndex < colonIndex) {
      candidates.add(normalized.slice(slashIndex, colonIndex));
    }
  }

  return Array.from(candidates);
};

/**
 * Checks if a model supports image input (vision capabilities).
 * Uses fuzzy matching to handle various model name formats.
 *
 * @param model - The model name to check (e.g., "gpt-4-vision", "openai/gpt-4-vision:latest")
 * @returns true if the model supports image input, false otherwise
 */
export const supportsImageInput = (model?: string | null): boolean => {
  if (!model) {
    return false;
  }

  // First try exact match
  const exact = VISION_CAPABILITIES.get(model);
  if (exact !== undefined) {
    return exact;
  }

  // Try fuzzy matching with candidate keys
  for (const key of candidateKeys(model)) {
    const capability = NORMALIZED_VISION_CAPABILITIES.get(key);
    if (capability !== undefined) {
      return capability;
    }
  }

  // Default to false if no match found
  return false;
};
