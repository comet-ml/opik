import React from "react";

import SliderInputControl from "@/components/shared/SliderInputControl/SliderInputControl";
import PromptModelSettingsTooltipContent from "@/components/pages-shared/llm/PromptModelSettings/providerConfigs/PromptModelConfigsTooltipContent";
import {
  LLMOpenAIConfigsType,
  PROVIDER_MODEL_TYPE,
  ReasoningEffort,
} from "@/types/providers";
import { DEFAULT_OPEN_AI_CONFIGS } from "@/constants/llm";
import isUndefined from "lodash/isUndefined";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { Label } from "@/components/ui/label";
import ExplainerIcon from "@/components/shared/ExplainerIcon/ExplainerIcon";

interface OpenAIModelSettingsProps {
  configs: Partial<LLMOpenAIConfigsType>;
  model?: PROVIDER_MODEL_TYPE | "";
  onChange: (configs: Partial<LLMOpenAIConfigsType>) => void;
}

const OpenAIModelConfigs = ({
  configs,
  model,
  onChange,
}: OpenAIModelSettingsProps) => {
  const isGpt5Model =
    model &&
    [
      PROVIDER_MODEL_TYPE.GPT_5,
      PROVIDER_MODEL_TYPE.GPT_5_MINI,
      PROVIDER_MODEL_TYPE.GPT_5_NANO,
    ].includes(model as PROVIDER_MODEL_TYPE);
  return (
    <div className="flex w-72 flex-col gap-6">
      {!isUndefined(configs.temperature) && (
        <SliderInputControl
          value={configs.temperature}
          onChange={(v) => onChange({ temperature: v })}
          id="temperature"
          min={0}
          max={1}
          step={0.01}
          defaultValue={DEFAULT_OPEN_AI_CONFIGS.TEMPERATURE}
          label="Temperature"
          tooltip={
            <PromptModelSettingsTooltipContent text="Controls randomness: Lowering results in less random completions. As the temperature approaches zero, the model will become deterministic and repetitive." />
          }
        />
      )}

      {!isUndefined(configs.maxCompletionTokens) && (
        <SliderInputControl
          value={configs.maxCompletionTokens}
          onChange={(v) => onChange({ maxCompletionTokens: v })}
          id="maxCompletionTokens"
          min={0}
          max={128000}
          step={1}
          defaultValue={DEFAULT_OPEN_AI_CONFIGS.MAX_COMPLETION_TOKENS}
          label="Max output tokens"
          tooltip={
            <PromptModelSettingsTooltipContent text="The maximum number of tokens to generate shared between the prompt and completion. The exact limit varies by model. (One token is roughly 4 characters for standard English text)." />
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
          defaultValue={DEFAULT_OPEN_AI_CONFIGS.TOP_P}
          label="Top P"
          tooltip={
            <PromptModelSettingsTooltipContent text="Controls diversity via nucleus sampling: 0.5 means half of all likelihood-weighted options are considered" />
          }
        />
      )}

      {!isUndefined(configs.frequencyPenalty) && (
        <SliderInputControl
          value={configs.frequencyPenalty}
          onChange={(v) => onChange({ frequencyPenalty: v })}
          id="frequencyPenalty"
          min={0}
          max={1}
          step={0.01}
          defaultValue={DEFAULT_OPEN_AI_CONFIGS.FREQUENCY_PENALTY}
          label="Frequency penalty"
          tooltip={
            <PromptModelSettingsTooltipContent text="How much to penalize new tokens based on their existing frequency in the text so far. Decreases the model's likelihood to repeat the same line verbatim" />
          }
        />
      )}

      {!isUndefined(configs.presencePenalty) && (
        <SliderInputControl
          value={configs.presencePenalty}
          onChange={(v) => onChange({ presencePenalty: v })}
          id="presencePenalty"
          min={0}
          max={1}
          step={0.01}
          defaultValue={DEFAULT_OPEN_AI_CONFIGS.PRESENCE_PENALTY}
          label="Presence penalty"
          tooltip={
            <PromptModelSettingsTooltipContent text="How much to penalize new tokens based on whether they appear in the text so far. Increases the model's likelihood to talk about new topics" />
          }
        />
      )}

      {isGpt5Model && (
        <div className="space-y-2">
          <div className="flex items-center space-x-2">
            <Label htmlFor="reasoningEffort" className="text-sm font-medium">
              Reasoning effort
            </Label>
            <ExplainerIcon description="Controls how much effort the model puts into reasoning before responding. Higher effort may result in more thoughtful but slower responses." />
          </div>
          <Select
            value={configs.reasoningEffort || "medium"}
            onValueChange={(value: ReasoningEffort) =>
              onChange({ reasoningEffort: value })
            }
          >
            <SelectTrigger>
              <SelectValue placeholder="Select reasoning effort" />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="minimal">Minimal</SelectItem>
              <SelectItem value="low">Low</SelectItem>
              <SelectItem value="medium">Medium</SelectItem>
              <SelectItem value="high">High</SelectItem>
            </SelectContent>
          </Select>
        </div>
      )}
    </div>
  );
};

export default OpenAIModelConfigs;
