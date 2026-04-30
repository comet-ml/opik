import {
  AnthropicThinkingEffort,
  PROVIDER_MODEL_TYPE,
  COMPOSED_PROVIDER_TYPE,
} from "@/types/providers";
import {
  ANTHROPIC_MODEL_CAPABILITIES,
  ANTHROPIC_THINKING_EFFORT_LABELS,
  REASONING_MODELS,
} from "@/constants/llm";
import { PROVIDER_TYPE } from "@/types/providers";
import { parseComposedProviderType } from "@/lib/provider";
import { getLatestModelFlags } from "@/lib/modelRegistryStore";

/**
 * Checks if a model is a reasoning model that requires temperature = 1.0.
 *
 * Primary source: the backend-fetched registry (via the module-level flag
 * index populated by useLLMProviderModelsData). A new reasoning model added
 * to the CDN YAML gets the temp=1 gate automatically.
 *
 * Fallback: the hardcoded REASONING_MODELS list (src/constants/llm.ts). Used
 * only before the first fetch resolves, or for non-React callers that run
 * before any component has mounted.
 */
export const isReasoningModel = (model?: PROVIDER_MODEL_TYPE | ""): boolean => {
  if (!model) return false;
  const fetched = getLatestModelFlags(model);
  if (fetched !== undefined) {
    return fetched.reasoning;
  }
  return (REASONING_MODELS as readonly PROVIDER_MODEL_TYPE[]).includes(
    model as PROVIDER_MODEL_TYPE,
  );
};

/**
 * Returns the default temperature for a given model
 * Reasoning models require temperature = 1.0, other models default to 0
 *
 * @param model - The model type
 * @returns 1.0 for reasoning models, 0 for all other models
 */
export const getDefaultTemperatureForModel = (
  model?: PROVIDER_MODEL_TYPE | "",
): number => {
  return isReasoningModel(model) ? 1 : 0;
};

/**
 * Checks if a Gemini model supports thinking level parameter
 * Currently Gemini 3 Pro and Gemini 3 Flash support thinking level
 *
 * @param model - The model type to check
 * @returns true if the model supports thinking level, false otherwise
 */
export const supportsGeminiThinkingLevel = (
  model?: PROVIDER_MODEL_TYPE | "",
): boolean => {
  return (
    model === PROVIDER_MODEL_TYPE.GEMINI_3_1_PRO ||
    model === PROVIDER_MODEL_TYPE.GEMINI_3_PRO ||
    model === PROVIDER_MODEL_TYPE.GEMINI_3_FLASH
  );
};

/**
 * Checks if a Vertex AI model supports thinking level parameter
 * Currently only Vertex AI Gemini 3 Pro supports thinking level
 *
 * @param model - The model type to check
 * @returns true if the model supports thinking level, false otherwise
 */
export const supportsVertexAIThinkingLevel = (
  model?: PROVIDER_MODEL_TYPE | "",
): boolean => {
  return (
    model === PROVIDER_MODEL_TYPE.VERTEX_AI_GEMINI_3_1_PRO ||
    model === PROVIDER_MODEL_TYPE.VERTEX_AI_GEMINI_3_PRO
  );
};

const anthropicCapabilities = (model?: PROVIDER_MODEL_TYPE | "") =>
  model
    ? ANTHROPIC_MODEL_CAPABILITIES[model as PROVIDER_MODEL_TYPE]
    : undefined;

export const supportsSamplingParams = (
  model?: PROVIDER_MODEL_TYPE | "",
): boolean => anthropicCapabilities(model)?.supportsSamplingParams ?? true;

export const supportsAnthropicThinkingEffort = (
  model?: PROVIDER_MODEL_TYPE | "",
): boolean => !!anthropicCapabilities(model)?.thinkingEffortOptions;

export const getAnthropicThinkingEffortOptions = (
  model?: PROVIDER_MODEL_TYPE | "",
): Array<{ label: string; value: AnthropicThinkingEffort }> =>
  (anthropicCapabilities(model)?.thinkingEffortOptions ?? []).map((value) => ({
    label: ANTHROPIC_THINKING_EFFORT_LABELS[value],
    value,
  }));

/**
 * Reconciles a provider config against the newly-selected model. Called from
 * every model-change handler (playground, LLM-as-judge rule dialog) so each
 * site shares one set of rules:
 *
 * - OpenAI reasoning models require temperature >= 1.
 * - Anthropic models that reject sampling params drop temperature/topP.
 * - Anthropic models with thinking-effort coerce a stale value to "high"
 *   (or drop it entirely if the new model has no thinking-effort dropdown).
 */
export const updateProviderConfig = <
  T extends {
    temperature?: number;
    topP?: number;
    thinkingEffort?: AnthropicThinkingEffort;
  },
>(
  currentConfig: T | undefined,
  params: {
    model: PROVIDER_MODEL_TYPE | "";
    provider: COMPOSED_PROVIDER_TYPE;
  },
): T | undefined => {
  if (!currentConfig) {
    return currentConfig;
  }

  const providerType = parseComposedProviderType(params.provider);

  if (
    providerType === PROVIDER_TYPE.OPEN_AI &&
    isReasoningModel(params.model) &&
    typeof currentConfig.temperature === "number" &&
    currentConfig.temperature < 1
  ) {
    return {
      ...currentConfig,
      temperature: 1.0,
    };
  }

  if (providerType === PROVIDER_TYPE.ANTHROPIC) {
    const next: T = { ...currentConfig };
    let changed = false;

    if (!supportsSamplingParams(params.model)) {
      if (next.temperature !== undefined) {
        next.temperature = undefined;
        changed = true;
      }
      if (next.topP !== undefined) {
        next.topP = undefined;
        changed = true;
      }
    }

    const effortOptions = getAnthropicThinkingEffortOptions(params.model);
    if (effortOptions.length === 0) {
      if (next.thinkingEffort !== undefined) {
        next.thinkingEffort = undefined;
        changed = true;
      }
    } else if (
      next.thinkingEffort !== undefined &&
      !effortOptions.some((o) => o.value === next.thinkingEffort)
    ) {
      next.thinkingEffort = "high";
      changed = true;
    }

    return changed ? next : currentConfig;
  }

  return currentConfig;
};
