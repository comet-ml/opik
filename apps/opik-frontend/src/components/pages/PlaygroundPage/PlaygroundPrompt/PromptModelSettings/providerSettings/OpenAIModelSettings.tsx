import SliderInputControl from "@/components/shared/SliderInputControl/SliderInputControl";
import PromptModelSettingsTooltipContent from "@/components/pages/PlaygroundPage/PlaygroundPrompt/PromptModelSettings/providerSettings/PromptModelSettingsTooltip";
import { Label } from "@/components/ui/label";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import { Info } from "lucide-react";
import { Input } from "@/components/ui/input";
import React, { useState } from "react";

const TEMPERATURE_DEFAULT_VALUE = 0;
const MAX_OUTPUT_TOKENS_DEFAULT_VALUE = 1024;
const TOP_P_DEFAULT_VALUE = 1;
const FREQUENCY_PENALTY_DEFAULT_VALUE = 0;
const PRESENCE_PENALTY_DEFAULT_VALUE = 0;

const OpenAIModelSettings = () => {
  const [temperature, setTemperature] = useState(TEMPERATURE_DEFAULT_VALUE);
  const [maxOutputTokens, setMaxOutputTokens] = useState(
    MAX_OUTPUT_TOKENS_DEFAULT_VALUE,
  );
  const [topP, setTopP] = useState(TOP_P_DEFAULT_VALUE);
  const [frequencyPenalty, setFrequencyPenalty] = useState(
    FREQUENCY_PENALTY_DEFAULT_VALUE,
  );
  const [presencePenalty, setPresencePenalty] = useState(
    PRESENCE_PENALTY_DEFAULT_VALUE,
  );
  const [stopSequences, setStopSequences] = useState("");

  return (
    <div className="flex w-72 flex-col gap-6">
      <SliderInputControl
        value={temperature}
        onChange={setTemperature}
        id="temperature"
        min={0}
        max={1}
        step={0.01}
        defaultValue={TEMPERATURE_DEFAULT_VALUE}
        label="Temperature"
        tooltip={
          <PromptModelSettingsTooltipContent text="Controls randomness: Lowering results in less random completions. As the temperature approaches zero, the model will become deterministic and repetitive." />
        }
      />

      <SliderInputControl
        value={maxOutputTokens}
        onChange={setMaxOutputTokens}
        id="maxOutputTokens"
        min={0}
        max={10000}
        step={1}
        defaultValue={MAX_OUTPUT_TOKENS_DEFAULT_VALUE}
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
          id="stopSequences"
          value={stopSequences}
          onChange={(event) => setStopSequences(event.target.value)}
        />
      </div>

      <SliderInputControl
        value={topP}
        onChange={setTopP}
        id="topP"
        min={0}
        max={1}
        step={0.01}
        defaultValue={TOP_P_DEFAULT_VALUE}
        label="Top P"
        tooltip={
          <PromptModelSettingsTooltipContent text="Controls diversity via nucleus sampling: 0.5 means half of all likelihood-weighted options are considered" />
        }
      />

      <SliderInputControl
        value={frequencyPenalty}
        onChange={setFrequencyPenalty}
        id="frequencyPenalty"
        min={0}
        max={1}
        step={0.01}
        defaultValue={FREQUENCY_PENALTY_DEFAULT_VALUE}
        label="Frequency penalty"
        tooltip={
          <PromptModelSettingsTooltipContent text="How much to penalize new tokens based on their existing frequency in the text so far. Decreases the model's likelihood to repeat the same line verbatim" />
        }
      />

      <SliderInputControl
        value={presencePenalty}
        onChange={setPresencePenalty}
        id="presencePenalty"
        min={0}
        max={1}
        step={0.01}
        defaultValue={PRESENCE_PENALTY_DEFAULT_VALUE}
        label="Presence penalty"
        tooltip={
          <PromptModelSettingsTooltipContent text="How much to penalize new tokens based on whether they appear in the text so far. Increases the model's likelihood to talk about new topics" />
        }
      />
    </div>
  );
};

export default OpenAIModelSettings;
