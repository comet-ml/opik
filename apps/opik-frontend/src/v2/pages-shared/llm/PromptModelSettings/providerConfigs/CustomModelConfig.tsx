import React, { useCallback } from "react";
import CodeMirror from "@uiw/react-codemirror";
import { EditorView } from "@codemirror/view";
import { jsonLanguage } from "@codemirror/lang-json";

import SliderInputControl from "@/shared/SliderInputControl/SliderInputControl";
import PromptModelSettingsTooltipContent from "@/v2/pages-shared/llm/PromptModelSettings/providerConfigs/PromptModelConfigsTooltipContent";
import {
  LLMCustomConfigsType,
  PROVIDER_MODEL_TYPE,
  PROVIDER_TYPE,
} from "@/types/providers";
import { DEFAULT_CUSTOM_CONFIGS } from "@/constants/llm";
import { useCodemirrorTheme } from "@/hooks/useCodemirrorTheme";
import useJsonInput from "@/hooks/useJsonInput";
import { Label } from "@/ui/label";
import { FormErrorSkeleton } from "@/ui/form";
import isUndefined from "lodash/isUndefined";
import { resolveSamplingParams } from "@/lib/modelUtils";
import ExclusiveSamplingParams, {
  resolveSamplingPresentation,
} from "@/v2/pages-shared/llm/PromptModelSettings/providerConfigs/ExclusiveSamplingParams";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import { Info } from "lucide-react";
import { ModelConfigParam } from "@/v2/pages-shared/llm/PromptModelSettings/modelConfigParams";

const TOKEN_LIMIT_KEYS = ["max_tokens", "max_completion_tokens"];

// The backend moves an extra body token limit, typed under either name, to the one name these providers read.
const TOKEN_LIMIT_KEY_SENT: Partial<Record<PROVIDER_TYPE, string>> = {
  [PROVIDER_TYPE.OLLAMA]: "max_tokens",
  [PROVIDER_TYPE.BEDROCK]: "max_completion_tokens",
};

interface CustomModelConfigProps {
  configs: Partial<LLMCustomConfigsType>;
  onChange: (configs: Partial<LLMCustomConfigsType>) => void;
  model?: PROVIDER_MODEL_TYPE | "";
  provider?: PROVIDER_TYPE;
  unsupportedParams?: ReadonlySet<ModelConfigParam>;
}

const CustomModelConfig = ({
  configs,
  onChange,
  model,
  provider,
  unsupportedParams,
}: CustomModelConfigProps) => {
  const supports = (param: ModelConfigParam) => !unsupportedParams?.has(param);
  const sampling = resolveSamplingPresentation(model);
  const { temperature, topP } = resolveSamplingParams(model ?? "", configs);
  const theme = useCodemirrorTheme({ editable: true });

  const handleExtraBodyParametersChange = useCallback(
    (value: Record<string, unknown> | null) => {
      onChange({ custom_parameters: value });
    },
    [onChange],
  );

  const { jsonString, showInvalidJSON, handleJsonChange, handleJsonBlur } =
    useJsonInput({
      value: configs.custom_parameters,
      onChange: handleExtraBodyParametersChange,
    });
  const inExtraBody = (key: string) =>
    configs.custom_parameters?.[key] !== undefined;
  const sendsTemperature = !isUndefined(temperature);
  const sendsTopP = supports("topP") && !isUndefined(topP);
  const sendsTokenLimit =
    supports("maxCompletionTokens") &&
    !isUndefined(configs.maxCompletionTokens);
  const tokenLimitKeys = TOKEN_LIMIT_KEYS.filter(inExtraBody);
  const tokenLimitKeySent = provider && TOKEN_LIMIT_KEY_SENT[provider];
  const tokenLimitNames = !tokenLimitKeySent
    ? tokenLimitKeys
    : tokenLimitKeys.includes(tokenLimitKeySent)
      ? [tokenLimitKeySent]
      : tokenLimitKeys.map((key) => `${key} (as ${tokenLimitKeySent})`);
  const sliderKeysInExtraBody = [
    ...(sendsTemperature && inExtraBody("temperature") ? ["temperature"] : []),
    ...(sendsTopP && inExtraBody("top_p") ? ["top_p"] : []),
    ...(sendsTokenLimit ? tokenLimitNames : []),
    ...(!isUndefined(configs.frequencyPenalty) &&
    inExtraBody("frequency_penalty")
      ? ["frequency_penalty"]
      : []),
    ...(!isUndefined(configs.presencePenalty) && inExtraBody("presence_penalty")
      ? ["presence_penalty"]
      : []),
  ];
  const [sliderKeyNextToExtraBody, extraBodyKeyNextToSlider] =
    sampling !== "exclusive"
      ? []
      : sendsTemperature && inExtraBody("top_p")
        ? ["temperature", "top_p"]
        : sendsTopP && inExtraBody("temperature")
          ? ["top_p", "temperature"]
          : [];

  return (
    <div className="flex w-72 flex-col gap-6">
      {sampling === "none" ? null : sampling === "exclusive" ? (
        <ExclusiveSamplingParams
          temperature={temperature}
          topP={topP}
          temperatureDefault={DEFAULT_CUSTOM_CONFIGS.TEMPERATURE}
          topPDefault={DEFAULT_CUSTOM_CONFIGS.TOP_P}
          temperatureMin={0}
          offerChoice={supports("topP")}
          onChange={onChange}
        />
      ) : (
        <>
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
        </>
      )}

      {supports("maxCompletionTokens") &&
        !isUndefined(configs.maxCompletionTokens) && (
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

      {sampling === "independent" &&
        supports("topP") &&
        !isUndefined(configs.topP) && (
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

      {supports("throttling") && (
        <SliderInputControl
          value={configs.throttling ?? DEFAULT_CUSTOM_CONFIGS.THROTTLING}
          onChange={(v) => onChange({ throttling: v })}
          id="throttling"
          min={0}
          max={10}
          step={0.1}
          defaultValue={DEFAULT_CUSTOM_CONFIGS.THROTTLING}
          label="Throttling (seconds)"
          tooltip={
            <PromptModelSettingsTooltipContent text="Minimum time in seconds between consecutive requests to avoid rate limiting" />
          }
        />
      )}

      {supports("maxConcurrentRequests") && (
        <SliderInputControl
          value={
            configs.maxConcurrentRequests ??
            DEFAULT_CUSTOM_CONFIGS.MAX_CONCURRENT_REQUESTS
          }
          onChange={(v) => onChange({ maxConcurrentRequests: v })}
          id="maxConcurrentRequests"
          min={1}
          max={20}
          step={1}
          defaultValue={DEFAULT_CUSTOM_CONFIGS.MAX_CONCURRENT_REQUESTS}
          label="Max concurrent requests"
          tooltip={
            <PromptModelSettingsTooltipContent text="Maximum number of requests that can run simultaneously. Set to 1 for sequential execution, higher values for parallel processing" />
          }
        />
      )}

      <div className="flex flex-col gap-2">
        <Label htmlFor="custom_parameters" className="flex items-center gap-1">
          Extra body parameters (Optional)
          <TooltipWrapper
            content={
              <PromptModelSettingsTooltipContent text="Provider-specific JSON parameters sent with each request" />
            }
          >
            <Info className="ml-1 size-4 text-light-slate" />
          </TooltipWrapper>
        </Label>
        <div className="max-h-52 overflow-y-auto rounded-md border">
          <CodeMirror
            id="custom_parameters"
            theme={theme}
            value={jsonString}
            onChange={handleJsonChange}
            onBlur={handleJsonBlur}
            extensions={[jsonLanguage, EditorView.lineWrapping]}
            placeholder='{"key": "value"}'
            basicSetup={{
              lineNumbers: false,
              foldGutter: false,
              highlightActiveLine: false,
              highlightSelectionMatches: false,
            }}
          />
        </div>
        {showInvalidJSON ? (
          <FormErrorSkeleton>
            {configs.custom_parameters
              ? "Invalid JSON, not saved. Runs keep the last valid JSON."
              : "Invalid JSON, not saved."}
          </FormErrorSkeleton>
        ) : (
          <>
            {sliderKeysInExtraBody.length > 0 && (
              <p className="comet-body-xs text-light-slate">
                Sent instead of the slider: {sliderKeysInExtraBody.join(", ")}
              </p>
            )}
            {extraBodyKeyNextToSlider && (
              <p className="comet-body-xs text-light-slate">
                {extraBodyKeyNextToSlider} is sent next to{" "}
                {sliderKeyNextToExtraBody}. Claude may reject the two together.
              </p>
            )}
          </>
        )}
      </div>
    </div>
  );
};

export default CustomModelConfig;
