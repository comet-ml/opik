import React, { useCallback } from "react";

import SliderInputControl from "@/shared/SliderInputControl/SliderInputControl";
import {
  LLMAnthropicConfigsType,
  PROVIDER_MODEL_TYPE,
  AnthropicThinkingEffort,
} from "@/types/providers";
import { DEFAULT_ANTHROPIC_CONFIGS } from "@/constants/llm";
import PromptModelConfigsTooltipContent from "@/v2/pages-shared/llm/PromptModelSettings/providerConfigs/PromptModelConfigsTooltipContent";
import { ToggleGroup, ToggleGroupItem } from "@/ui/toggle-group";
import {
  getAnthropicThinkingEffortOptions,
  resolveEffort,
  resolveSamplingParams,
  supportsSamplingParams,
} from "@/lib/modelUtils";
import SelectBox from "@/shared/SelectBox/SelectBox";
import { Label } from "@/ui/label";
import ExplainerIcon from "@/shared/ExplainerIcon/ExplainerIcon";
import isNil from "lodash/isNil";
import { ModelConfigParam } from "@/v2/pages-shared/llm/PromptModelSettings/modelConfigParams";

interface AnthropicModelConfigsProps {
  configs: LLMAnthropicConfigsType;
  onChange: (configs: Partial<LLMAnthropicConfigsType>) => void;
  model?: PROVIDER_MODEL_TYPE | "";
  unsupportedParams?: ReadonlySet<ModelConfigParam>;
}

const AnthropicModelConfigs = ({
  configs,
  onChange,
  model,
  unsupportedParams,
}: AnthropicModelConfigsProps) => {
  const supports = (param: ModelConfigParam) => !unsupportedParams?.has(param);
  const showSamplingParams = supportsSamplingParams(model);
  const thinkingEffortOptions = getAnthropicThinkingEffortOptions(model);
  // Read the pair through the resolver rather than off the config: it is what the request will
  // carry, and it guarantees exactly one half is live, which is what the choice below reflects.
  const { temperature, topP } = resolveSamplingParams(model ?? "", configs);
  const { thinkingEffort } = resolveEffort(model ?? "", configs);
  // Top P only counts as live where the surface can actually store one.
  const topPLive = !isNil(topP) && supports("topP");

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

  // Switching hands the incoming parameter its default and clears the outgoing one, because the
  // config holds whichever is live and Anthropic rejects a request carrying both.
  const handleSamplingParamChange = useCallback(
    (value: string) => {
      if (value === "topP") {
        onChange({
          topP: DEFAULT_ANTHROPIC_CONFIGS.TOP_P,
          temperature: undefined,
        });
      } else if (value === "temperature") {
        onChange({
          temperature: DEFAULT_ANTHROPIC_CONFIGS.TEMPERATURE,
          topP: undefined,
        });
      }
    },
    [onChange],
  );

  return (
    <div className="flex w-72 flex-col gap-6">
      {showSamplingParams && (
        <div className="space-y-2">
          {supports("topP") && (
            <>
              <div className="flex items-center space-x-2">
                <Label className="text-sm font-medium">Sampling</Label>
                <ExplainerIcon description="Anthropic models take either Temperature or Top P, not both. Pick the one you want to tune." />
              </div>
              <ToggleGroup
                type="single"
                variant="secondary"
                value={topPLive ? "topP" : "temperature"}
                onValueChange={handleSamplingParamChange}
                className="w-full"
              >
                <ToggleGroupItem value="temperature" className="flex-1">
                  Temperature
                </ToggleGroupItem>
                <ToggleGroupItem value="topP" className="flex-1">
                  Top P
                </ToggleGroupItem>
              </ToggleGroup>
            </>
          )}
          {topPLive ? (
            <SliderInputControl
              value={topP}
              onChange={handleTopPChange}
              id="topP"
              min={0}
              max={1}
              step={0.01}
              defaultValue={DEFAULT_ANTHROPIC_CONFIGS.TOP_P}
              label="Top P"
              tooltip={
                <PromptModelConfigsTooltipContent text="Controls diversity via nucleus sampling: 0.5 means half of all likelihood-weighted options are considered." />
              }
            />
          ) : (
            <SliderInputControl
              value={temperature}
              onChange={handleTemperatureChange}
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
          )}
        </div>
      )}

      {supports("maxCompletionTokens") && (
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
      )}

      {supports("throttling") && (
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
      )}

      {supports("maxConcurrentRequests") && (
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
      )}

      {supports("thinkingEffort") && thinkingEffort !== undefined && (
        <div className="space-y-2">
          <div className="flex items-center space-x-2">
            <Label htmlFor="thinkingEffort" className="text-sm font-medium">
              Thinking effort
            </Label>
            <ExplainerIcon description="Controls how much effort Claude puts into thinking before responding. Higher effort produces more thorough analysis but takes longer. Uses adaptive thinking mode." />
          </div>
          <SelectBox
            id="thinkingEffort"
            value={thinkingEffort}
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
