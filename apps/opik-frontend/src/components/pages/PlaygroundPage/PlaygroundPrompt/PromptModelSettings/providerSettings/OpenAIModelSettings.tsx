import React from "react";

import SliderInputControl from "@/components/shared/SliderInputControl/SliderInputControl";
import PromptModelSettingsTooltipContent from "@/components/pages/PlaygroundPage/PlaygroundPrompt/PromptModelSettings/providerSettings/PromptModelSettingsTooltip";
import { Label } from "@/components/ui/label";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import { Info } from "lucide-react";
import { Input } from "@/components/ui/input";
import { PlaygroundOpenAIConfigsType } from "@/types/playgroundPrompts";
import { DEFAULT_OPEN_AI_CONFIGS } from "@/constants/playground";

interface OpenAIModelSettingsProps {
  configs: PlaygroundOpenAIConfigsType;
  onChange: (configs: Partial<PlaygroundOpenAIConfigsType>) => void;
}

// ALEX MAKE IT CONFIGS
const OpenAIModelSettings = ({
  configs,
  onChange,
}: OpenAIModelSettingsProps) => {
  return (
    <div className="flex w-72 flex-col gap-6">
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

      <SliderInputControl
        value={configs.maxTokens}
        onChange={(v) => onChange({ maxTokens: v })}
        id="maxTokens"
        min={0}
        max={10000}
        step={1}
        defaultValue={DEFAULT_OPEN_AI_CONFIGS.MAX_TOKENS}
        label="Max output tokens"
        tooltip={
          <PromptModelSettingsTooltipContent text="The maximum number of tokens to generate shared between the prompt and completion. The exact limit varies by model. (One token is roughly 4 characters for standard English text)." />
        }
      />

      <div>
        <div className="mb-2 flex items-center">
          <Label htmlFor="stop">Stop sequences</Label>

          <TooltipWrapper
            content={
              <PromptModelSettingsTooltipContent text="Up to four sequences where the API will stop generating further tokens. The returned text will not contain the stop sequence" />
            }
          >
            <Info className="ml-1 size-4 text-light-slate" />
          </TooltipWrapper>
        </div>

        <Input
          id="stop"
          value={configs.stop}
          onChange={(event) => onChange({ stop: event.target.value })}
        />
      </div>

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
    </div>
  );
};

export default OpenAIModelSettings;
