import React from "react";

import SliderInputControl from "@/components/shared/SliderInputControl/SliderInputControl";
import { LLMAnthropicConfigsType } from "@/types/providers";
import { DEFAULT_ANTHROPIC_CONFIGS } from "@/constants/llm";
import PromptModelConfigsTooltipContent from "@/components/pages-shared/llm/PromptModelSettings/providerConfigs/PromptModelConfigsTooltipContent";

interface AnthropicModelConfigsProps {
  configs: LLMAnthropicConfigsType;
  onChange: (configs: Partial<LLMAnthropicConfigsType>) => void;
}

const AnthropicModelConfigs = ({
  configs,
  onChange,
}: AnthropicModelConfigsProps) => {
  return (
    <div className="flex w-72 flex-col gap-6">
      <SliderInputControl
        value={configs.temperature}
        onChange={(v) => onChange({ temperature: v })}
        id="temperature"
        min={0}
        max={1}
        step={0.01}
        defaultValue={DEFAULT_ANTHROPIC_CONFIGS.TEMPERATURE}
        label="Temperature"
        tooltip={
          <PromptModelConfigsTooltipContent text="Controls randomness: Lowering results in less random completions. As the temperature approaches zero, the model will become deterministic and repetitive." />
        }
      />

      <SliderInputControl
        value={configs.maxCompletionTokens}
        onChange={(v) => onChange({ maxCompletionTokens: v })}
        id="maxCompletionTokens"
        min={0}
        max={10000}
        step={1}
        defaultValue={DEFAULT_ANTHROPIC_CONFIGS.MAX_COMPLETION_TOKENS}
        label="Max output tokens"
        tooltip={
          <PromptModelConfigsTooltipContent text="The maximum number of tokens to generate shared between the prompt and completion. The exact limit varies by model. (One token is roughly 4 characters for standard English text)." />
        }
      />

      <SliderInputControl
        value={configs.topP}
        onChange={(v) => onChange({ topP: v })}
        id="topP"
        min={0}
        max={1}
        step={0.01}
        defaultValue={DEFAULT_ANTHROPIC_CONFIGS.TOP_P}
        label="Top P"
        tooltip={
          <PromptModelConfigsTooltipContent text="Controls diversity via nucleus sampling: 0.5 means half of all likelihood-weighted options are considered" />
        }
      />
    </div>
  );
};

export default AnthropicModelConfigs;
