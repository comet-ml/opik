import {
  hasEffortWithoutThinking,
  thinksOnlyWhenAsked,
} from "@/lib/modelUtils";
import { PROVIDER_MODEL_TYPE } from "@/types/providers";

export const getAnthropicEffortDescription = (
  model?: PROVIDER_MODEL_TYPE | "",
): string => {
  if (hasEffortWithoutThinking(model)) {
    return "Changes how long Claude's answers are and how readily it calls tools. It doesn't turn on thinking.";
  }
  if (thinksOnlyWhenAsked(model)) {
    return "Off answers without thinking. At any other level, Claude can think before it answers and decides how much; at lower levels it often answers straight away. Effort also controls how many tokens Claude spends on the whole response. Lower effort is faster and cheaper; higher effort is more thorough.";
  }
  return "Controls how many tokens Claude spends on the whole response, thinking included. Lower effort is faster and cheaper; higher effort is more thorough.";
};

export const getSamplingOffNote = (
  withTopP: boolean,
  thinkingFromCustomParameters: boolean,
): string => {
  const params = withTopP ? "Temperature and Top P are" : "Temperature is";
  if (thinkingFromCustomParameters) {
    return `${params} off because the custom parameters turn thinking on.`;
  }
  return `${params} off while Claude can think. Set Thinking effort to Off to use ${
    withTopP ? "them" : "it"
  }.`;
};
