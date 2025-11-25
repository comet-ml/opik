import React from "react";

import SliderInputControl from "@/components/shared/SliderInputControl/SliderInputControl";
import { LLMGeminiConfigsType, PROVIDER_MODEL_TYPE } from "@/types/providers";
import {
  DEFAULT_GEMINI_CONFIGS,
  THINKING_LEVEL_OPTIONS,
} from "@/constants/llm";
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

      {isGemini3Pro && (
        <div className="space-y-2">
          <div className="flex items-center space-x-2">
            <Label htmlFor="thinkingLevel" className="text-sm font-medium">
              Thinking level
            </Label>
            <ExplainerIcon description="Controls the depth of reasoning the model performs before responding. Higher thinking level may result in more thorough but slower responses." />
          </div>
          <SelectBox
            id="thinkingLevel"
            value={configs.thinkingLevel || "low"}
            onChange={(value: "low" | "high") =>
              onChange({ thinkingLevel: value })
            }
            options={THINKING_LEVEL_OPTIONS}
            placeholder="Select thinking level"
          />
        </div>
      )}
    </div>
  );
};

export default GeminiModelConfigs;
