import React, { useCallback } from "react";

import SliderInputControl from "@/components/shared/SliderInputControl/SliderInputControl";
import {
  LLMAnthropicConfigsType,
  PROVIDER_MODEL_TYPE,
  AnthropicThinkingEffort,
} from "@/types/providers";
import {
  DEFAULT_ANTHROPIC_CONFIGS,
  ANTHROPIC_THINKING_EFFORT_OPTIONS,
} from "@/constants/llm";
import PromptModelConfigsTooltipContent from "@/components/pages-shared/llm/PromptModelSettings/providerConfigs/PromptModelConfigsTooltipContent";
import { Button } from "@/components/ui/button";
import { X } from "lucide-react";
import { supportsAnthropicThinkingEffort } from "@/lib/modelUtils";
import SelectBox from "@/components/shared/SelectBox/SelectBox";
import { Label } from "@/components/ui/label";
import ExplainerIcon from "@/components/shared/ExplainerIcon/ExplainerIcon";
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
            value={configs.thinkingEffort || "high"}
            onChange={(value: AnthropicThinkingEffort) =>
              onChange({ thinkingEffort: value })
            }
            options={ANTHROPIC_THINKING_EFFORT_OPTIONS}
            placeholder="Select thinking effort"
          />
        </div>
      )}
    </div>
  );
};

export default AnthropicModelConfigs;
