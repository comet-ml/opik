import React from "react";

import SliderInputControl from "@/shared/SliderInputControl/SliderInputControl";
import {
  GeminiThinkingLevel,
  LLMVertexAIConfigsType,
  PROVIDER_MODEL_TYPE,
} from "@/types/providers";
import { DEFAULT_VERTEX_AI_CONFIGS } from "@/constants/llm";
import PromptModelConfigsTooltipContent from "@/v2/pages-shared/llm/PromptModelSettings/providerConfigs/PromptModelConfigsTooltipContent";
import {
  createSupports,
  getVertexAIVisibleControls,
  isAnyControlVisible,
} from "@/v2/pages-shared/llm/PromptModelSettings/providerConfigs/visibleControls";
import SelectBox from "@/shared/SelectBox/SelectBox";
import { Label } from "@/ui/label";
import ExplainerIcon from "@/shared/ExplainerIcon/ExplainerIcon";
import {
  getDefaultThinkingLevel,
  getThinkingLevelOptions,
  resolveSamplingParams,
} from "@/lib/modelUtils";
import { ModelConfigParam } from "@/v2/pages-shared/llm/PromptModelSettings/modelConfigParams";

interface VertexAIModelConfigsProps {
  configs: LLMVertexAIConfigsType;
  model?: PROVIDER_MODEL_TYPE | "";
  onChange: (configs: Partial<LLMVertexAIConfigsType>) => void;
  unsupportedParams?: ReadonlySet<ModelConfigParam>;
}

const VertexAIModelConfigs = ({
  configs,
  model,
  onChange,
  unsupportedParams,
}: VertexAIModelConfigsProps) => {
  const thinkingLevelOptions = getThinkingLevelOptions(model);
  const defaultThinkingLevel = getDefaultThinkingLevel(model);
  const { temperature, topP } = resolveSamplingParams(model ?? "", configs);
  const visible = getVertexAIVisibleControls({
    model,
    configs,
    supports: createSupports(unsupportedParams),
  });

  if (!isAnyControlVisible(visible)) return null;

  return (
    <div className="flex w-72 flex-col gap-6">
      {visible.temperature && (
        <SliderInputControl
          value={temperature}
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

      {visible.maxCompletionTokens && (
        <SliderInputControl
          value={configs.maxCompletionTokens}
          onChange={(v) => onChange({ maxCompletionTokens: v })}
          id="maxOutputTokens"
          min={0}
          max={65535}
          step={1}
          defaultValue={DEFAULT_VERTEX_AI_CONFIGS.MAX_COMPLETION_TOKENS}
          label="Max output tokens"
          tooltip={
            <PromptModelConfigsTooltipContent text="The maximum number of tokens the model can generate in its response. The prompt does not count toward it. On thinking models, thinking tokens count toward it too, so a low limit can leave the response empty. The exact limit varies by model. (One token is roughly 4 characters for standard English text)." />
          }
        />
      )}

      {visible.topP && (
        <SliderInputControl
          value={topP}
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

      {visible.thinkingLevel && (
        <div className="space-y-2">
          <div className="flex items-center space-x-2">
            <Label htmlFor="thinkingLevel" className="text-sm font-medium">
              Thinking level
            </Label>
            <ExplainerIcon description="Controls the depth of reasoning the model performs before responding. Higher thinking level may result in more thorough but slower responses." />
          </div>
          <SelectBox
            id="thinkingLevel"
            value={configs.thinkingLevel || defaultThinkingLevel}
            onChange={(value: GeminiThinkingLevel) =>
              onChange({ thinkingLevel: value })
            }
            options={thinkingLevelOptions}
            placeholder="Select thinking level"
          />
        </div>
      )}

      {visible.throttling && (
        <SliderInputControl
          value={configs.throttling ?? DEFAULT_VERTEX_AI_CONFIGS.THROTTLING}
          onChange={(v) => onChange({ throttling: v })}
          id="throttling"
          min={0}
          max={10}
          step={0.1}
          defaultValue={DEFAULT_VERTEX_AI_CONFIGS.THROTTLING}
          label="Throttling (seconds)"
          tooltip={
            <PromptModelConfigsTooltipContent text="Minimum time in seconds between consecutive requests to avoid rate limiting" />
          }
        />
      )}

      {visible.maxConcurrentRequests && (
        <SliderInputControl
          value={
            configs.maxConcurrentRequests ??
            DEFAULT_VERTEX_AI_CONFIGS.MAX_CONCURRENT_REQUESTS
          }
          onChange={(v) => onChange({ maxConcurrentRequests: v })}
          id="maxConcurrentRequests"
          min={1}
          max={20}
          step={1}
          defaultValue={DEFAULT_VERTEX_AI_CONFIGS.MAX_CONCURRENT_REQUESTS}
          label="Max concurrent requests"
          tooltip={
            <PromptModelConfigsTooltipContent text="Maximum number of requests that can run simultaneously. Set to 1 for sequential execution, higher values for parallel processing" />
          }
        />
      )}
    </div>
  );
};

export default VertexAIModelConfigs;
