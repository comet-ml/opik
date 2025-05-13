import React from "react";

import SliderInputControl from "@/components/shared/SliderInputControl/SliderInputControl";
import { LLMVertexAIConfigsType } from "@/types/providers";
import { DEFAULT_VERTEX_AI_CONFIGS } from "@/constants/llm";
import PromptModelConfigsTooltipContent from "@/components/pages-shared/llm/PromptModelSettings/providerConfigs/PromptModelConfigsTooltipContent";
import isUndefined from "lodash/isUndefined";

interface VertexAIModelConfigsProps {
  configs: LLMVertexAIConfigsType;
  onChange: (configs: Partial<LLMVertexAIConfigsType>) => void;
}

const VertexAIModelConfigs = ({
  configs,
  onChange,
}: VertexAIModelConfigsProps) => {
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
          defaultValue={DEFAULT_VERTEX_AI_CONFIGS.TEMPERATURE}
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
          max={10000}
          step={1}
          defaultValue={DEFAULT_VERTEX_AI_CONFIGS.MAX_COMPLETION_TOKENS}
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
          defaultValue={DEFAULT_VERTEX_AI_CONFIGS.TOP_P}
          label="Top P"
          tooltip={
            <PromptModelConfigsTooltipContent text="Controls diversity via nucleus sampling: 0.5 means half of all likelihood-weighted options are considered" />
          }
        />
      )}
    </div>
  );
};

export default VertexAIModelConfigs;
