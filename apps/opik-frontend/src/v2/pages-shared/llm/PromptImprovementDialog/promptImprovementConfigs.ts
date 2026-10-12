import {
  getAnthropicThinkingEffortOptions,
  getOpenAIReasoningEffortOptions,
  getThinkingLevelOptions,
} from "@/lib/modelUtils";
import {
  GeminiThinkingLevel,
  LLMPromptConfigsType,
  PROVIDER_MODEL_TYPE,
} from "@/types/providers";

const offersLow = (options: Array<{ value: string }>) =>
  options.some((option) => option.value === "low");

const THINKING_BELOW_LOW: ReadonlySet<GeminiThinkingLevel | undefined> =
  new Set(["off", "none", "minimal"]);

// Writing a prompt needs little reasoning. At the default high effort, GPT-5 Nano spent the whole
// 4000-token default on reasoning and returned no text; at low it finished in under 1000 tokens.
export const withLowReasoning = (
  model: PROVIDER_MODEL_TYPE | "",
  configs: LLMPromptConfigsType,
): LLMPromptConfigsType => {
  const next: Record<string, unknown> = { ...configs };

  if (offersLow(getOpenAIReasoningEffortOptions(model))) {
    next.reasoningEffort = "low";
  }

  // The options run from least to most reasoning, so the first is the least the model takes. Where
  // a Claude model offers Off, that is Off rather than Low, which would turn its thinking on.
  const [leastEffort] = getAnthropicThinkingEffortOptions(model);
  if (leastEffort) {
    next.thinkingEffort = leastEffort.value;
  }

  const thinkingLevel = next.thinkingLevel as GeminiThinkingLevel | undefined;
  if (
    offersLow(getThinkingLevelOptions(model)) &&
    !THINKING_BELOW_LOW.has(thinkingLevel)
  ) {
    next.thinkingLevel = "low";
  }

  return next as LLMPromptConfigsType;
};
