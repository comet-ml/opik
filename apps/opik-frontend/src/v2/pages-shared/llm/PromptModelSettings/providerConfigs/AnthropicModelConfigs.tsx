import React, { useCallback, useEffect } from "react";

import SliderInputControl from "@/shared/SliderInputControl/SliderInputControl";
import {
  LLMAnthropicConfigsType,
  PROVIDER_MODEL_TYPE,
  AnthropicThinkingEffort,
} from "@/types/providers";
import { DEFAULT_ANTHROPIC_CONFIGS } from "@/constants/llm";
import PromptModelConfigsTooltipContent from "@/v2/pages-shared/llm/PromptModelSettings/providerConfigs/PromptModelConfigsTooltipContent";
import { Button } from "@/ui/button";
import { X } from "lucide-react";
import {
  getAnthropicThinkingEffortOptions,
  supportsAnthropicThinkingEffort,
  supportsSamplingParams,
} from "@/lib/modelUtils";
import SelectBox from "@/shared/SelectBox/SelectBox";
import { Label } from "@/ui/label";
import ExplainerIcon from "@/shared/ExplainerIcon/ExplainerIcon";
import isNil from "lodash/isNil";

interface AnthropicModelConfigsProps {
  configs: LLMAnthropicConfigsType;
  onChange: (configs: Partial<LLMAnthropicConfigsType>) => void;
  model?: PROVIDER_MODEL_TYPE | "";
}

const AnthropicModelConfigs = ({
  configs,
  onChange,
  model,
}: AnthropicModelConfigsProps) => {
  const showThinkingEffort = supportsAnthropicThinkingEffort(model);
  const showSamplingParams = supportsSamplingParams(model);
  const thinkingEffortOptions = getAnthropicThinkingEffortOptions(model);
  // Persisted prompts may carry an effort that's not valid for the current
  // model (e.g. "adaptive" picked under Opus 4.6, then switched to Opus 4.7).
  // Normalize the form state so the outbound request never carries a stale
  // value the model would reject.
  const isEffortValid = thinkingEffortOptions.some(
    (o) => o.value === configs.thinkingEffort,
  );
  const effortValue: AnthropicThinkingEffort = isEffortValid
    ? (configs.thinkingEffort as AnthropicThinkingEffort)
    : "high";
  useEffect(() => {
    if (showThinkingEffort && !isEffortValid) {
      onChange({ thinkingEffort: "high" });
    }
  }, [showThinkingEffort, isEffortValid, onChange]);
  const hasTemperatureValue = !isNil(configs.temperature);
  const hasTopPValue = !isNil(configs.topP);
  const temperatureDisabled = hasTopPValue && !hasTemperatureValue;
  const topPDisabled = hasTemperatureValue && !hasTopPValue;

  const handleTemperatureChange = useCallback(
    (v: number) => {
      onChange({ temperature: v, topP: undefined });
    },
    [onChange],
  );

  const handleTopPChange = useCallback(
    (v: number) => {
      onChange({ topP: v, temperature: undefined });
    },
    [onChange],
  );

  const handleClearTemperature = useCallback(() => {
    onChange({
      temperature: undefined,
      topP: DEFAULT_ANTHROPIC_CONFIGS.TOP_P,
    });
  }, [onChange]);

  const handleClearTopP = useCallback(() => {
    onChange({
      topP: undefined,
      temperature: DEFAULT_ANTHROPIC_CONFIGS.TEMPERATURE,
    });
  }, [onChange]);

  return (
    <div className="flex w-72 flex-col gap-6">
      {showSamplingParams && (
        <div className="space-y-2">
          <div
            className={
              temperatureDisabled ? "pointer-events-none opacity-50" : ""
            }
          >
            <SliderInputControl
              value={
                configs.temperature ??
                (temperatureDisabled
                  ? undefined
                  : DEFAULT_ANTHROPIC_CONFIGS.TEMPERATURE)
              }
              onChange={handleTemperatureChange}
              id="temperature"
              min={0}
              max={1}
              step={0.01}
              defaultValue={DEFAULT_ANTHROPIC_CONFIGS.TEMPERATURE}
              label="Temperature"
              tooltip={
                <PromptModelConfigsTooltipContent text="Controls randomness: Lowering results in less random completions. As the temperature approaches zero, the model will become deterministic and repetitive. Note: Anthropic models require using either Temperature OR Top P, not both." />
              }
            />
          </div>
          {hasTemperatureValue && (
            <div className="flex justify-end">
              <Button
                variant="ghost"
                size="2xs"
                onClick={handleClearTemperature}
                aria-label="Clear temperature to use Top P"
              >
                <X className="mr-1 size-3" />
                Clear to use Top P
              </Button>
            </div>
          )}
        </div>
      )}

      <SliderInputControl
        value={
          configs.maxCompletionTokens ??
          DEFAULT_ANTHROPIC_CONFIGS.MAX_COMPLETION_TOKENS
        }
        onChange={(v) => onChange({ maxCompletionTokens: v })}
        id="maxCompletionTokens"
        min={0}
        max={64000}
        step={1}
        defaultValue={DEFAULT_ANTHROPIC_CONFIGS.MAX_COMPLETION_TOKENS}
        label="Max output tokens"
        tooltip={
          <PromptModelConfigsTooltipContent text="The maximum number of tokens to generate shared between the prompt and completion. The exact limit varies by model. (One token is roughly 4 characters for standard English text)." />
        }
      />

      {showSamplingParams && (
        <div className="space-y-2">
          <div className={topPDisabled ? "pointer-events-none opacity-50" : ""}>
            <SliderInputControl
              value={
                configs.topP ??
                (topPDisabled ? undefined : DEFAULT_ANTHROPIC_CONFIGS.TOP_P)
              }
              onChange={handleTopPChange}
              id="topP"
              min={0}
              max={1}
              step={0.01}
              defaultValue={DEFAULT_ANTHROPIC_CONFIGS.TOP_P}
              label="Top P"
              tooltip={
                <PromptModelConfigsTooltipContent text="Controls diversity via nucleus sampling: 0.5 means half of all likelihood-weighted options are considered. Note: Anthropic models require using either Temperature OR Top P, not both." />
              }
            />
          </div>
          {hasTopPValue && (
            <div className="flex justify-end">
              <Button
                variant="ghost"
                size="2xs"
                onClick={handleClearTopP}
                aria-label="Clear Top P to use temperature"
              >
                <X className="mr-1 size-3" />
                Clear to use Temperature
              </Button>
            </div>
          )}
        </div>
      )}

      <SliderInputControl
        value={configs.throttling ?? DEFAULT_ANTHROPIC_CONFIGS.THROTTLING}
        onChange={(v) => onChange({ throttling: v })}
        id="throttling"
        min={0}
        max={10}
        step={0.1}
        defaultValue={DEFAULT_ANTHROPIC_CONFIGS.THROTTLING}
        label="Throttling (seconds)"
        tooltip={
          <PromptModelConfigsTooltipContent text="Minimum time in seconds between consecutive requests to avoid rate limiting" />
        }
      />

      <SliderInputControl
        value={
          configs.maxConcurrentRequests ??
          DEFAULT_ANTHROPIC_CONFIGS.MAX_CONCURRENT_REQUESTS
        }
        onChange={(v) => onChange({ maxConcurrentRequests: v })}
        id="maxConcurrentRequests"
        min={1}
        max={20}
        step={1}
        defaultValue={DEFAULT_ANTHROPIC_CONFIGS.MAX_CONCURRENT_REQUESTS}
        label="Max concurrent requests"
        tooltip={
          <PromptModelConfigsTooltipContent text="Maximum number of requests that can run simultaneously. Set to 1 for sequential execution, higher values for parallel processing" />
        }
      />

      {showThinkingEffort && (
        <div className="space-y-2">
          <div className="flex items-center space-x-2">
            <Label htmlFor="thinkingEffort" className="text-sm font-medium">
              Thinking effort
            </Label>
            <ExplainerIcon description="Controls how much effort Claude puts into thinking before responding. Higher effort produces more thorough analysis but takes longer. Uses adaptive thinking mode." />
          </div>
          <SelectBox
            id="thinkingEffort"
            value={effortValue}
            onChange={(value: AnthropicThinkingEffort) =>
              onChange({ thinkingEffort: value })
            }
            options={thinkingEffortOptions}
            placeholder="Select thinking effort"
          />
        </div>
      )}
    </div>
  );
};

export default AnthropicModelConfigs;
