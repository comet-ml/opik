import React from "react";

import SliderInputControl from "@/components/shared/SliderInputControl/SliderInputControl";
import { LLMGeminiConfigsType, PROVIDER_MODEL_TYPE } from "@/types/providers";
import {
  DEFAULT_GEMINI_CONFIGS,
  THINKING_LEVEL_OPTIONS_PRO,
  THINKING_LEVEL_OPTIONS_FLASH,
} from "@/constants/llm";
import { GeminiThinkingLevel } from "@/types/providers";
import PromptModelConfigsTooltipContent from "@/components/pages-shared/llm/PromptModelSettings/providerConfigs/PromptModelConfigsTooltipContent";
import isUndefined from "lodash/isUndefined";
import SelectBox from "@/components/shared/SelectBox/SelectBox";
import { Label } from "@/components/ui/label";
import ExplainerIcon from "@/components/shared/ExplainerIcon/ExplainerIcon";

interface geminiModelConfigsProps {
  configs: LLMGeminiConfigsType;
  model?: PROVIDER_MODEL_TYPE | "";
  onChange: (configs: Partial<LLMGeminiConfigsType>) => void;
}

const GeminiModelConfigs = ({
  configs,
  model,
  onChange,
}: geminiModelConfigsProps) => {
  const isGemini3Pro = model === PROVIDER_MODEL_TYPE.GEMINI_3_PRO;
  const isGemini3Flash = model === PROVIDER_MODEL_TYPE.GEMINI_3_FLASH;
  const hasThinkingLevel = isGemini3Pro || isGemini3Flash;

  // Get appropriate options based on model
  // Flash supports all 4 levels (minimal, low, medium, high)
  // Pro supports only 2 levels (low, high)
  // Both default to "high" (dynamic reasoning)
  const thinkingLevelOptions = isGemini3Flash
    ? THINKING_LEVEL_OPTIONS_FLASH
    : THINKING_LEVEL_OPTIONS_PRO;
  const defaultThinkingLevel = "high";

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

      {hasThinkingLevel && (
        <div className="space-y-2">
          <div className="flex items-center space-x-2">
            <Label htmlFor="thinkingLevel" className="text-sm font-medium">
              Thinking level
            </Label>
            <ExplainerIcon description="Controls the depth of reasoning the model performs before responding. Higher thinking level may result in more thorough but slower responses." />
          </div>
          <SelectBox
            id="thinkingLevel"
            value={configs.thinkingLevel || defaultThinkingLevel}
            onChange={(value: GeminiThinkingLevel) =>
              onChange({ thinkingLevel: value })
            }
            options={thinkingLevelOptions}
            placeholder="Select thinking level"
          />
        </div>
      )}

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
        value={
          configs.maxConcurrentRequests ??
          DEFAULT_GEMINI_CONFIGS.MAX_CONCURRENT_REQUESTS
        }
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
