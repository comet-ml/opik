import React, { useMemo, useCallback } from "react";

import SliderInputControl from "@/components/shared/SliderInputControl/SliderInputControl";
import {
  LLMAnthropicConfigsType,
  PROVIDER_MODEL_TYPE,
} from "@/types/providers";
import { DEFAULT_ANTHROPIC_CONFIGS } from "@/constants/llm";
import PromptModelConfigsTooltipContent from "@/components/pages-shared/llm/PromptModelSettings/providerConfigs/PromptModelConfigsTooltipContent";
import { Button } from "@/components/ui/button";
import { X } from "lucide-react";

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
  const isExclusiveParamModel = useMemo(() => {
    return (
      model === PROVIDER_MODEL_TYPE.CLAUDE_OPUS_4_5 ||
      model === PROVIDER_MODEL_TYPE.CLAUDE_OPUS_4_1 ||
      model === PROVIDER_MODEL_TYPE.CLAUDE_SONNET_4_5 ||
      model === PROVIDER_MODEL_TYPE.CLAUDE_HAIKU_4_5
    );
  }, [model]);

  const hasTemperatureValue = useMemo(() => {
    return configs.temperature !== null && configs.temperature !== undefined;
  }, [configs.temperature]);

  const hasTopPValue = useMemo(() => {
    return configs.topP !== null && configs.topP !== undefined;
  }, [configs.topP]);

  const temperatureDisabled = useMemo(() => {
    return isExclusiveParamModel && hasTopPValue && !hasTemperatureValue;
  }, [isExclusiveParamModel, hasTopPValue, hasTemperatureValue]);

  const topPDisabled = useMemo(() => {
    return isExclusiveParamModel && hasTemperatureValue && !hasTopPValue;
  }, [isExclusiveParamModel, hasTemperatureValue, hasTopPValue]);

  const handleTemperatureChange = useCallback(
    (v: number) => {
      if (isExclusiveParamModel && hasTopPValue) {
        // Clear topP when setting temperature for models that require exclusive params
        onChange({ temperature: v, topP: undefined });
      } else {
        onChange({ temperature: v });
      }
    },
    [isExclusiveParamModel, hasTopPValue, onChange],
  );

  const handleTopPChange = useCallback(
    (v: number) => {
      if (isExclusiveParamModel && hasTemperatureValue) {
        // Clear temperature when setting topP for models that require exclusive params
        onChange({ topP: v, temperature: undefined });
      } else {
        onChange({ topP: v });
      }
    },
    [isExclusiveParamModel, hasTemperatureValue, onChange],
  );

  const handleClearTemperature = useCallback(() => {
    if (isExclusiveParamModel) {
      // For models requiring exclusive params, clearing temperature should enable topP by setting a default
      onChange({
        temperature: undefined,
        topP: DEFAULT_ANTHROPIC_CONFIGS.TOP_P,
      });
    } else {
      onChange({ temperature: undefined });
    }
  }, [isExclusiveParamModel, onChange]);

  const handleClearTopP = useCallback(() => {
    if (isExclusiveParamModel) {
      // For models requiring exclusive params, clearing topP should enable temperature by setting a default
      onChange({
        topP: undefined,
        temperature: DEFAULT_ANTHROPIC_CONFIGS.TEMPERATURE,
      });
    } else {
      onChange({ topP: undefined });
    }
  }, [isExclusiveParamModel, onChange]);

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
              <PromptModelConfigsTooltipContent
                text={`Controls randomness: Lowering results in less random completions. As the temperature approaches zero, the model will become deterministic and repetitive.${
                  isExclusiveParamModel
                    ? " Note: This model requires using either Temperature OR Top P, not both."
                    : ""
                }`}
              />
            }
          />
        </div>
        {isExclusiveParamModel && hasTemperatureValue && (
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
              <PromptModelConfigsTooltipContent
                text={`Controls diversity via nucleus sampling: 0.5 means half of all likelihood-weighted options are considered.${
                  isExclusiveParamModel
                    ? " Note: This model requires using either Temperature OR Top P, not both."
                    : ""
                }`}
              />
            }
          />
        </div>
        {isExclusiveParamModel && hasTopPValue && (
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
    </div>
  );
};

export default AnthropicModelConfigs;
