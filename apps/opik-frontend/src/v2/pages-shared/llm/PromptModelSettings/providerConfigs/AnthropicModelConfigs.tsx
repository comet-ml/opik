import React from "react";

import SliderInputControl from "@/shared/SliderInputControl/SliderInputControl";
import {
  LLMAnthropicConfigsType,
  PROVIDER_MODEL_TYPE,
  AnthropicThinkingEffort,
} from "@/types/providers";
import { DEFAULT_ANTHROPIC_CONFIGS } from "@/constants/llm";
import PromptModelConfigsTooltipContent from "@/v2/pages-shared/llm/PromptModelSettings/providerConfigs/PromptModelConfigsTooltipContent";
import ExclusiveSamplingParams from "@/v2/pages-shared/llm/PromptModelSettings/providerConfigs/ExclusiveSamplingParams";
import {
  getAnthropicThinkingEffortOptions,
  resolveEffort,
  resolveSamplingParams,
  supportsSamplingParams,
} from "@/lib/modelUtils";
import SelectBox from "@/shared/SelectBox/SelectBox";
import { Label } from "@/ui/label";
import ExplainerIcon from "@/shared/ExplainerIcon/ExplainerIcon";
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

  return (
    <div className="flex w-72 flex-col gap-6">
      {showSamplingParams && (
        <ExclusiveSamplingParams
          temperature={temperature}
          topP={topP}
          temperatureDefault={DEFAULT_ANTHROPIC_CONFIGS.TEMPERATURE}
          topPDefault={DEFAULT_ANTHROPIC_CONFIGS.TOP_P}
          offerChoice={supports("topP")}
          onChange={onChange}
        />
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
