import React from "react";

import SliderInputControl from "@/components/shared/SliderInputControl/SliderInputControl";
import PromptModelSettingsTooltipContent from "@/components/pages-shared/llm/PromptModelSettings/providerConfigs/PromptModelConfigsTooltipContent";
import { LLMCustomConfigsType } from "@/types/providers";
import { DEFAULT_CUSTOM_CONFIGS } from "@/constants/llm";
import isUndefined from "lodash/isUndefined";
import { Label } from "@/components/ui/label";
import { Textarea } from "@/components/ui/textarea";

interface CustomModelConfigProps {
  configs: Partial<LLMCustomConfigsType>;
  onChange: (configs: Partial<LLMCustomConfigsType>) => void;
}

const CustomModelConfig = ({ configs, onChange }: CustomModelConfigProps) => {
  const [extraBodyError, setExtraBodyError] = React.useState<string>("");

  const handleExtraBodyChange = (value: string) => {
    // Clear error when value is empty
    if (!value || value.trim() === "") {
      setExtraBodyError("");
      onChange({ extraBody: value });
      return;
    }

    // Validate JSON
    try {
      JSON.parse(value);
      setExtraBodyError("");
      onChange({ extraBody: value });
    } catch (error) {
      setExtraBodyError("Must be valid JSON format");
      // Still update the value to allow editing
      onChange({ extraBody: value });
    }
  };

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
          defaultValue={DEFAULT_CUSTOM_CONFIGS.TEMPERATURE}
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
          max={10000}
          step={1}
          defaultValue={DEFAULT_CUSTOM_CONFIGS.MAX_COMPLETION_TOKENS}
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
          defaultValue={DEFAULT_CUSTOM_CONFIGS.TOP_P}
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
          defaultValue={DEFAULT_CUSTOM_CONFIGS.FREQUENCY_PENALTY}
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
          defaultValue={DEFAULT_CUSTOM_CONFIGS.PRESENCE_PENALTY}
          label="Presence penalty"
          tooltip={
            <PromptModelSettingsTooltipContent text="How much to penalize new tokens based on whether they appear in the text so far. Increases the model's likelihood to talk about new topics" />
          }
        />
      )}

      <div className="flex flex-col gap-2">
        <div className="flex items-center gap-2">
          <Label htmlFor="extraBody">Extra body parameters</Label>
          <PromptModelSettingsTooltipContent text="Provider-specific JSON parameters sent with each request" />
        </div>
        <Textarea
          id="extraBody"
          placeholder='{"key1": {"key2": 2, "key3": true}}'
          value={configs.extraBody || ""}
          onChange={(e) => handleExtraBodyChange(e.target.value)}
          rows={4}
          className={extraBodyError ? "border-destructive" : ""}
        />
        {extraBodyError && (
          <p className="text-sm font-medium text-destructive">
            {extraBodyError}
          </p>
        )}
      </div>
    </div>
  );
};

export default CustomModelConfig;
