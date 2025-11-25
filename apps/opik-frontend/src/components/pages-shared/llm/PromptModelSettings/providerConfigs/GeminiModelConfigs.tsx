import React from "react";

import SliderInputControl from "@/components/shared/SliderInputControl/SliderInputControl";
import { LLMGeminiConfigsType } from "@/types/providers";
import { DEFAULT_GEMINI_CONFIGS } from "@/constants/llm";
import PromptModelConfigsTooltipContent from "@/components/pages-shared/llm/PromptModelSettings/providerConfigs/PromptModelConfigsTooltipContent";
import isUndefined from "lodash/isUndefined";

interface geminiModelConfigsProps {
  configs: LLMGeminiConfigsType;
  onChange: (configs: Partial<LLMGeminiConfigsType>) => void;
}

const GeminiModelConfigs = ({ configs, onChange }: geminiModelConfigsProps) => {
  return (
    <div className="flex w-72 flex-col gap-6">
      {!isUndefined(configs.temperature) && (
        <SliderInputControl
          value={configs.temperature}
          onChange={(v) => onChange({ temperature: v })}
          id="temperature"
          min={0}
          max={2}
          step={0.01}
          defaultValue={DEFAULT_GEMINI_CONFIGS.TEMPERATURE}
          label="Temperature"
          tooltip={
            <PromptModelConfigsTooltipContent text="Controls randomness: Lowering results in less random completions. As the temperature approaches zero, the model will become deterministic and repetitive." />
          }
        />
      )}

      {!isUndefined(configs.maxCompletionTokens) && (
        <SliderInputControl
          value={configs.maxCompletionTokens}
          onChange={(v) => onChange({ maxCompletionTokens: v })}
          id="maxOutputTokens"
          min={0}
          max={65535}
          step={1}
          defaultValue={DEFAULT_GEMINI_CONFIGS.MAX_COMPLETION_TOKENS}
          label="Max output tokens"
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
          defaultValue={DEFAULT_GEMINI_CONFIGS.TOP_P}
          label="Top P"
          tooltip={
            <PromptModelConfigsTooltipContent text="Controls diversity via nucleus sampling: 0.5 means half of all likelihood-weighted options are considered" />
          }
        />
      )}

      <SliderInputControl
        value={configs.maxRetries ?? DEFAULT_GEMINI_CONFIGS.MAX_RETRIES}
        onChange={(v) => onChange({ maxRetries: v })}
        id="maxRetries"
        min={0}
        max={10}
        step={1}
        defaultValue={DEFAULT_GEMINI_CONFIGS.MAX_RETRIES}
        label="Max retries"
        tooltip={
          <PromptModelConfigsTooltipContent text="Maximum number of retry attempts for failed requests" />
        }
      />

      <SliderInputControl
        value={configs.timeout ?? DEFAULT_GEMINI_CONFIGS.TIMEOUT}
        onChange={(v) => onChange({ timeout: v })}
        id="timeout"
        min={1}
        max={300}
        step={1}
        defaultValue={DEFAULT_GEMINI_CONFIGS.TIMEOUT}
        label="Timeout (seconds)"
        tooltip={
          <PromptModelConfigsTooltipContent text="Maximum time in seconds to wait for a response before timing out" />
        }
      />

      <SliderInputControl
        value={configs.backoffExponent ?? DEFAULT_GEMINI_CONFIGS.BACKOFF_EXPONENT}
        onChange={(v) => onChange({ backoffExponent: v })}
        id="backoffExponent"
        min={1}
        max={5}
        step={0.1}
        defaultValue={DEFAULT_GEMINI_CONFIGS.BACKOFF_EXPONENT}
        label="Backoff exponent"
        tooltip={
          <PromptModelConfigsTooltipContent text="Exponential backoff multiplier for retry delays. Higher values result in longer wait times between retries" />
        }
      />

      <SliderInputControl
        value={configs.throttling ?? DEFAULT_GEMINI_CONFIGS.THROTTLING}
        onChange={(v) => onChange({ throttling: v })}
        id="throttling"
        min={0}
        max={10}
        step={0.1}
        defaultValue={DEFAULT_GEMINI_CONFIGS.THROTTLING}
        label="Throttling (seconds)"
        tooltip={
          <PromptModelConfigsTooltipContent text="Minimum time in seconds between consecutive requests to avoid rate limiting" />
        }
      />

      <SliderInputControl
        value={configs.maxConcurrentRequests ?? DEFAULT_GEMINI_CONFIGS.MAX_CONCURRENT_REQUESTS}
        onChange={(v) => onChange({ maxConcurrentRequests: v })}
        id="maxConcurrentRequests"
        min={1}
        max={20}
        step={1}
        defaultValue={DEFAULT_GEMINI_CONFIGS.MAX_CONCURRENT_REQUESTS}
        label="Max concurrent requests"
        tooltip={
          <PromptModelConfigsTooltipContent text="Maximum number of requests that can run simultaneously. Set to 1 for sequential execution, higher values for parallel processing" />
        }
      />
    </div>
  );
};

export default GeminiModelConfigs;
