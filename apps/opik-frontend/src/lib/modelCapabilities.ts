import modelPricing from "@/data/model_prices_and_context_window.json";
import { CUSTOM_PROVIDER_MODEL_PREFIX } from "@/constants/providers";

type ModelPricingEntry = {
  supports_vision?: boolean;
  supports_video_input?: boolean;
  [key: string]: unknown;
};

/**
 * Normalizes model names for consistent lookup.
 * - Converts to lowercase
 * - Trims whitespace
 * - Replaces dots with hyphens (fixes issue #4114 for vision capability detection)
 *
 * This ensures "claude-3.5-sonnet" matches "claude-3-5-sonnet" in the pricing database.
 */
const normalizeModelName = (value: string) =>
  value.trim().toLowerCase().replace(/\./g, "-");

const modelEntries = modelPricing as Record<string, ModelPricingEntry>;

const VISION_CAPABILITIES = new Map<string, boolean>();
const NORMALIZED_VISION_CAPABILITIES = new Map<string, boolean>();

// Hardcoded vision-capable models or patterns that should support vision
// This list overrides the JSON configuration to handle cases where:
// - The JSON uses different naming conventions (e.g., openrouter/ prefix)
// - Models are missing from the JSON
// - The JSON is not yet updated with new vision models
const VISION_MODEL_PATTERNS = [
  /qwen.*vl/i, // Qwen VL models (e.g., qwen/qwen-vl-plus, qwen2.5-vl-32b-instruct)
];

/**
 * Checks if a model name matches any of the hardcoded vision patterns.
 */
const matchesVisionPattern = (modelName: string): boolean => {
  const normalized = normalizeModelName(modelName);
  return VISION_MODEL_PATTERNS.some((pattern) => pattern.test(normalized));
};

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
 * Checks if a model is from a custom provider.
 * Custom provider models are prefixed with the CUSTOM_PROVIDER_MODEL_PREFIX.
 *
 * @param model - The model name to check
 * @returns true if the model is from a custom provider, false otherwise
 */
export const isCustomProviderModel = (model: string): boolean => {
  return model.startsWith(`${CUSTOM_PROVIDER_MODEL_PREFIX}/`);
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

  // Check if it's a custom model from custom provider
  if (isCustomProviderModel(model)) {
    return true;
  }

  // Check hardcoded vision patterns first (highest priority)
  // This overrides JSON configuration to handle naming inconsistencies
  if (matchesVisionPattern(model)) {
    return true;
  }

  // First try exact match from JSON
  const exact = VISION_CAPABILITIES.get(model);
  if (exact !== undefined) {
    return exact;
  }

  // Try fuzzy matching with candidate keys from JSON
  for (const key of candidateKeys(model)) {
    const capability = NORMALIZED_VISION_CAPABILITIES.get(key);
    if (capability !== undefined) {
      return capability;
    }
  }

  // Default to false if no match found
  return false;
};

/**
 * Checks if a model supports video input.
 */
export const supportsVideoInput = (model?: string | null): boolean => {
  // When model_pricing.json includes a `supports_video_input` field, update this function to:
  // 1. Build a VIDEO_CAPABILITIES map (like VISION_CAPABILITIES) from the `supports_video_input` field.
  // 2. Use VIDEO_CAPABILITIES and normalized keys for lookup, similar to supportsImageInput.
  // 3. Remove or update this fallback to supportsImageInput as appropriate.
  // For now, video input support is assumed to match image input support.
  return supportsImageInput(model);
};
