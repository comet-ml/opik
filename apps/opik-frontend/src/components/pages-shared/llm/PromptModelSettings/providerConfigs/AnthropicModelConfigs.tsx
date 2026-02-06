import React, { useMemo, useCallback } from "react";

import SliderInputControl from "@/components/shared/SliderInputControl/SliderInputControl";
import {
  LLMAnthropicConfigsType,
  PROVIDER_MODEL_TYPE,
} from "@/types/providers";
import {
  ANTHROPIC_THINKING_EFFORT_OPTIONS,
  DEFAULT_ANTHROPIC_CONFIGS,
} from "@/constants/llm";
import PromptModelConfigsTooltipContent from "@/components/pages-shared/llm/PromptModelSettings/providerConfigs/PromptModelConfigsTooltipContent";
import { Button } from "@/components/ui/button";
import { X } from "lucide-react";
import { Label } from "@/components/ui/label";
import ExplainerIcon from "@/components/shared/ExplainerIcon/ExplainerIcon";
import SelectBox from "@/components/shared/SelectBox/SelectBox";
import { DropdownOption } from "@/types/shared";
import {
  getSupportedAnthropicEfforts,
  normalizeAnthropicEffortForModel,
  supportsAnthropicAdaptiveThinking,
  supportsAnthropicExtendedThinking,
} from "@/lib/modelUtils";

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
  const hasExtendedThinking = supportsAnthropicExtendedThinking(model);
  const hasAdaptiveThinking = supportsAnthropicAdaptiveThinking(model);
  const supportedEfforts = getSupportedAnthropicEfforts(model);

  const thinkingMode = hasExtendedThinking
    ? configs.thinkingMode || (hasAdaptiveThinking ? "adaptive" : "disabled")
    : "disabled";
  const thinkingModeOptions: DropdownOption<
    "disabled" | "adaptive" | "manual"
  >[] = [
    ...(hasAdaptiveThinking
      ? [{ label: "Adaptive (Recommended)", value: "adaptive" as const }]
      : []),
    { label: "Disabled", value: "disabled" },
    { label: "Manual (budget tokens)", value: "manual" },
  ];

  const thinkingEffort =
    normalizeAnthropicEffortForModel(configs.thinkingEffort, model) || "high";
  const thinkingBudgetTokens = configs.thinkingBudgetTokens || 1024;
  const anthropicEffortOptions = ANTHROPIC_THINKING_EFFORT_OPTIONS.filter(
    (option) => supportedEfforts.includes(option.value),
  );

  const buildThinkingCustomParameters = useCallback(
    (
      mode: "disabled" | "adaptive" | "manual",
      overrides?: {
        effort?: "low" | "medium" | "high" | "max";
        budgetTokens?: number;
      },
    ) => {
      const existing = (configs.custom_parameters || {}) as Record<
        string,
        unknown
      >;
      const effort = overrides?.effort ?? thinkingEffort;
      const budgetTokens = overrides?.budgetTokens ?? thinkingBudgetTokens;
      const normalizedEffort =
        normalizeAnthropicEffortForModel(effort, model) || effort;

      if (!hasExtendedThinking || mode === "disabled") {
        const next = { ...existing };
        delete next.thinking;
        if (supportedEfforts.length) {
          next.output_config = {
            effort: normalizedEffort,
          };
        } else {
          delete next.output_config;
        }
        return Object.keys(next).length ? next : null;
      }

      if (mode === "adaptive") {
        return {
          ...existing,
          thinking: {
            type: "adaptive",
          },
          output_config: {
            effort: normalizedEffort,
          },
        };
      }

      return {
        ...existing,
        ...(supportedEfforts.length
          ? {
              output_config: {
                effort: normalizedEffort,
              },
            }
          : {}),
        thinking: {
          type: "enabled",
          budget_tokens: budgetTokens,
        },
      };
    },
    [
      configs.custom_parameters,
      hasExtendedThinking,
      model,
      supportedEfforts.length,
      thinkingBudgetTokens,
      thinkingEffort,
    ],
  );

  const updateThinkingMode = useCallback(
    (mode: "disabled" | "adaptive" | "manual") => {
      onChange({
        thinkingMode: mode,
        custom_parameters: buildThinkingCustomParameters(mode),
      });
    },
    [buildThinkingCustomParameters, onChange],
  );

  const updateThinkingEffort = useCallback(
    (effort: "low" | "medium" | "high" | "max") => {
      onChange({
        thinkingEffort: effort,
        custom_parameters: buildThinkingCustomParameters(
          thinkingMode as "adaptive" | "manual" | "disabled",
          { effort },
        ),
      });
    },
    [buildThinkingCustomParameters, onChange, thinkingMode],
  );

  const updateThinkingBudget = useCallback(
    (budget: number) => {
      onChange({
        thinkingBudgetTokens: budget,
        custom_parameters: buildThinkingCustomParameters(
          thinkingMode as "adaptive" | "manual" | "disabled",
          { budgetTokens: budget },
        ),
      });
    },
    [buildThinkingCustomParameters, onChange, thinkingMode],
  );

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

      {(hasExtendedThinking || supportedEfforts.length > 0) && (
        <div className="space-y-4 rounded-md border p-3">
          {hasExtendedThinking && (
            <>
              <div className="flex items-center space-x-2">
                <Label htmlFor="thinkingMode" className="text-sm font-medium">
                  Thinking mode
                </Label>
                <ExplainerIcon description="Configure Anthropic extended thinking. Opus 4.6 supports adaptive thinking; older models support manual thinking with budget tokens." />
              </div>
              <SelectBox
                id="thinkingMode"
                value={thinkingMode}
                onChange={(value: "disabled" | "adaptive" | "manual") =>
                  updateThinkingMode(value)
                }
                options={thinkingModeOptions}
              />
            </>
          )}

          {supportedEfforts.length > 0 && (
            <div className="space-y-2">
              <div className="flex items-center space-x-2">
                <Label htmlFor="thinkingEffort" className="text-sm font-medium">
                  Effort
                </Label>
                <ExplainerIcon description="Controls response token spend behavior. For Opus 4.6, this is the recommended thinking-depth control with adaptive thinking." />
              </div>
              <SelectBox
                id="thinkingEffort"
                value={thinkingEffort}
                onChange={(value: "low" | "medium" | "high" | "max") =>
                  updateThinkingEffort(value)
                }
                options={anthropicEffortOptions}
                placeholder="Select effort"
              />
            </div>
          )}

          {hasExtendedThinking && thinkingMode === "manual" && (
            <SliderInputControl
              value={thinkingBudgetTokens}
              onChange={updateThinkingBudget}
              id="thinkingBudgetTokens"
              min={1024}
              max={32768}
              step={1}
              defaultValue={1024}
              label="Thinking budget tokens"
              tooltip={
                <PromptModelConfigsTooltipContent text="Maximum tokens allowed for manual extended thinking (`thinking.type=enabled`)." />
              }
            />
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
    </div>
  );
};

export default AnthropicModelConfigs;
