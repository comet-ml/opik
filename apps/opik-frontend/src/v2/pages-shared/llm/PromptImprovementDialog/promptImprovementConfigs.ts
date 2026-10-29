import {
  getAnthropicThinkingEffortOptions,
  getOpenAIReasoningEffortOptions,
  getThinkingLevelOptions,
} from "@/lib/modelUtils";
import { LLMPromptConfigsType, PROVIDER_MODEL_TYPE } from "@/types/providers";

const BELOW_LOW: ReadonlySet<unknown> = new Set(["off", "none", "minimal"]);

const canLowerToLow = (options: Array<{ value: string }>, current: unknown) =>
  options.some((option) => option.value === "low") && !BELOW_LOW.has(current);

// Writing a prompt needs little reasoning. At high effort, GPT-5 Nano spent the whole 4000-token
// default on reasoning and returned no text; at low it finished in under 1000 tokens.
export const withReasoningAtMostLow = (
  model: PROVIDER_MODEL_TYPE | "",
  configs: LLMPromptConfigsType,
): LLMPromptConfigsType => {
  const next: Record<string, unknown> = { ...configs };

  if (
    canLowerToLow(getOpenAIReasoningEffortOptions(model), next.reasoningEffort)
  ) {
    next.reasoningEffort = "low";
  }

  // The options run from least to most reasoning, so the first is the least the model takes. Where
  // a Claude model offers Off, that is Off rather than Low, which would turn its thinking on.
  const [leastEffort] = getAnthropicThinkingEffortOptions(model);
  if (leastEffort) {
    next.thinkingEffort = leastEffort.value;
  }

  if (canLowerToLow(getThinkingLevelOptions(model), next.thinkingLevel)) {
    next.thinkingLevel = "low";
  }

  return next as LLMPromptConfigsType;
};
