import React from "react";
import isUndefined from "lodash/isUndefined";

import SliderInputControl from "@/components/shared/SliderInputControl/SliderInputControl";
import { LLMOpenRouterConfigsType } from "@/types/providers";
import { DEFAULT_OPEN_ROUTER_CONFIGS } from "@/constants/llm";
import PromptModelConfigsTooltipContent from "@/components/pages-shared/llm/PromptModelSettings/providerConfigs/PromptModelConfigsTooltipContent";

interface OpenRouterModelConfigsProps {
  configs: LLMOpenRouterConfigsType;
  onChange: (configs: Partial<LLMOpenRouterConfigsType>) => void;
}

const OpenRouterModelConfigs = ({
  configs,
  onChange,
}: OpenRouterModelConfigsProps) => {
  return (
    <div className="flex w-72 flex-col gap-4">
      {!isUndefined(configs.temperature) && (
        <SliderInputControl
          value={configs.temperature}
          onChange={(v) => onChange({ temperature: v })}
          id="temperature"
          min={-1}
          max={1}
          step={0.01}
          defaultValue={DEFAULT_OPEN_ROUTER_CONFIGS.TEMPERATURE}
          label="Temperature"
          tooltip={
            <PromptModelConfigsTooltipContent text="Controls randomness: Lowering results in less random completions. As the temperature approaches zero, the model will become deterministic and repetitive." />
          }
        />
      )}
      {!isUndefined(configs.maxTokens) && (
        <SliderInputControl
          value={configs.maxTokens}
          onChange={(v) => onChange({ maxTokens: v })}
          id="maxTokens"
          min={0}
          max={10000}
          step={1}
          defaultValue={DEFAULT_OPEN_ROUTER_CONFIGS.MAX_TOKENS}
          label="Max tokens"
          tooltip={
            <PromptModelConfigsTooltipContent text="The maximum number of tokens to generate shared between the prompt and completion. The exact limit varies by model. (One token is roughly 4 characters for standard English text)." />
          }
        />
      )}
      {!isUndefined(configs.topP) && (
        <SliderInputControl
          value={configs.topP}
          onChange={(v) => onChange({ topP: v })}
          id="topP"
          min={0}
          max={1}
          step={0.01}
          defaultValue={DEFAULT_OPEN_ROUTER_CONFIGS.TOP_P}
          label="Top P"
          tooltip={
            <PromptModelConfigsTooltipContent text="Controls diversity via nucleus sampling: 0.5 means half of all likelihood-weighted options are considered" />
          }
        />
      )}
      {!isUndefined(configs.topK) && (
        <SliderInputControl
          value={configs.topK}
          onChange={(v) => onChange({ topK: v })}
          id="topK"
          min={0}
          max={100}
          step={0.01}
          defaultValue={DEFAULT_OPEN_ROUTER_CONFIGS.TOP_K}
          label="Top K"
          tooltip={
            <PromptModelConfigsTooltipContent text="This limits the model's choice of tokens at each step, making it choose from a smaller set. A value of 1 means the model will always pick the most likely next token, leading to predictable results. By default this setting is disabled, making the model to consider all choices." />
          }
        />
      )}
      {!isUndefined(configs.frequencyPenalty) && (
        <SliderInputControl
          value={configs.frequencyPenalty}
          onChange={(v) => onChange({ frequencyPenalty: v })}
          id="topK"
          min={-2}
          max={2}
          step={0.01}
          defaultValue={DEFAULT_OPEN_ROUTER_CONFIGS.FREQUENCY_PENALTY}
          label="Frequency penalty"
          tooltip={
            <PromptModelConfigsTooltipContent text="This setting aims to control the repetition of tokens based on how often they appear in the input. It tries to use less frequently those tokens that appear more in the input, proportional to how frequently they occur. Token penalty scales with the number of occurrences. Negative values will encourage token reuse." />
          }
        />
      )}
      {!isUndefined(configs.presencePenalty) && (
        <SliderInputControl
          value={configs.presencePenalty}
          onChange={(v) => onChange({ presencePenalty: v })}
          id="presencePenalty"
          min={-2}
          max={2}
          step={0.01}
          defaultValue={DEFAULT_OPEN_ROUTER_CONFIGS.PRESENCE_PENALTY}
          label="Presence penalty"
          tooltip={
            <PromptModelConfigsTooltipContent text="Adjusts how often the model repeats specific tokens already used in the input. Higher values make such repetition less likely, while negative values do the opposite. Token penalty does not scale with the number of occurrences. Negative values will encourage token reuse." />
          }
        />
      )}
      {!isUndefined(configs.repetitionPenalty) && (
        <SliderInputControl
          value={configs.repetitionPenalty}
          onChange={(v) => onChange({ repetitionPenalty: v })}
          id="repetitionPenalty"
          min={0}
          max={2}
          step={0.01}
          defaultValue={DEFAULT_OPEN_ROUTER_CONFIGS.REPETITION_PENALTY}
          label="Repetition penalty"
          tooltip={
            <PromptModelConfigsTooltipContent text="Helps to reduce the repetition of tokens from the input. A higher value makes the model less likely to repeat tokens, but too high a value can make the output less coherent (often with run-on sentences that lack small words). Token penalty scales based on original token's probability." />
          }
        />
      )}
      {!isUndefined(configs.minP) && (
        <SliderInputControl
          value={configs.minP}
          onChange={(v) => onChange({ minP: v })}
          id="minP"
          min={0}
          max={1}
          step={0.01}
          defaultValue={DEFAULT_OPEN_ROUTER_CONFIGS.MIN_P}
          label="Min P"
          tooltip={
            <PromptModelConfigsTooltipContent text="Represents the minimum probability for a token to be considered, relative to the probability of the most likely token. (The value changes depending on the confidence level of the most probable token.) If your Min-P is set to 0.1, that means it will only allow for tokens that are at least 1/10th as probable as the best possible option." />
          }
        />
      )}
      {!isUndefined(configs.topA) && (
        <SliderInputControl
          value={configs.topA}
          onChange={(v) => onChange({ topA: v })}
          id="topA"
          min={0}
          max={1}
          step={0.01}
          defaultValue={DEFAULT_OPEN_ROUTER_CONFIGS.TOP_A}
          label="Top A"
          tooltip={
            <PromptModelConfigsTooltipContent
              text={
                'Consider only the top tokens with "sufficiently high" probabilities based on the probability of the most likely token. Think of it like a dynamic Top-P. A lower Top-A value focuses the choices based on the highest probability token but with a narrower scope. A higher Top-A value does not necessarily affect the creativity of the output, but rather refines the filtering process based on the maximum probability.'
              }
            />
          }
        />
      )}
      <SliderInputControl
        value={configs.throttling ?? DEFAULT_OPEN_ROUTER_CONFIGS.THROTTLING}
        onChange={(v) => onChange({ throttling: v })}
        id="throttling"
        min={0}
        max={10}
        step={0.1}
        defaultValue={DEFAULT_OPEN_ROUTER_CONFIGS.THROTTLING}
        label="Throttling (seconds)"
        tooltip={
          <PromptModelConfigsTooltipContent text="Minimum time in seconds between consecutive requests to avoid rate limiting" />
        }
      />
      <SliderInputControl
        value={
          configs.maxConcurrentRequests ??
          DEFAULT_OPEN_ROUTER_CONFIGS.MAX_CONCURRENT_REQUESTS
        }
        onChange={(v) => onChange({ maxConcurrentRequests: v })}
        id="maxConcurrentRequests"
        min={1}
        max={20}
        step={1}
        defaultValue={DEFAULT_OPEN_ROUTER_CONFIGS.MAX_CONCURRENT_REQUESTS}
        label="Max concurrent requests"
        tooltip={
          <PromptModelConfigsTooltipContent text="Maximum number of requests that can run simultaneously. Set to 1 for sequential execution, higher values for parallel processing" />
        }
      />
    </div>
  );
};

export default OpenRouterModelConfigs;
