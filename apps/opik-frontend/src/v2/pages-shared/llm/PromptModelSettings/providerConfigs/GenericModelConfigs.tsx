import React from "react";

import SliderInputControl from "@/shared/SliderInputControl/SliderInputControl";
import SelectBox from "@/shared/SelectBox/SelectBox";
import { Label } from "@/ui/label";
import ExplainerIcon from "@/shared/ExplainerIcon/ExplainerIcon";
import PromptModelConfigsTooltipContent from "@/v2/pages-shared/llm/PromptModelSettings/providerConfigs/PromptModelConfigsTooltipContent";
import ExclusiveSamplingParams from "@/v2/pages-shared/llm/PromptModelSettings/providerConfigs/ExclusiveSamplingParams";
import { createSupports } from "@/v2/pages-shared/llm/PromptModelSettings/providerConfigs/visibleControls";
import {
  NO_OUTPUT_LIMIT_TOOLTIP,
  getNoOutputLimitHint,
} from "@/v2/pages-shared/llm/PromptModelSettings/providerConfigs/noOutputLimit";
import {
  GenericPanelPresentation,
  getShownControls,
  getShownValue,
} from "@/v2/pages-shared/llm/PromptModelSettings/providerConfigs/genericPanels";
import { ModelConfigParam } from "@/v2/pages-shared/llm/PromptModelSettings/modelConfigParams";
import {
  EffortControl,
  NumberControl,
  NumberParamName,
  ParamControl,
  SamplingChoiceControl,
} from "@/lib/modelParams";
import { ModelParams } from "@/types/modelParams";

const SLIDERS: Record<
  Exclude<NumberParamName, "max_output_tokens">,
  { label: string; tooltip: string }
> = {
  temperature: {
    label: "Temperature",
    tooltip:
      "Controls randomness: Lowering results in less random completions. As the temperature approaches zero, the model will become deterministic and repetitive.",
  },
  top_p: {
    label: "Top P",
    tooltip:
      "Controls diversity via nucleus sampling: 0.5 means half of all likelihood-weighted options are considered.",
  },
  frequency_penalty: {
    label: "Frequency penalty",
    tooltip:
      "How much to penalize new tokens based on their existing frequency in the text so far. Decreases the model's likelihood to repeat the same line verbatim",
  },
  presence_penalty: {
    label: "Presence penalty",
    tooltip:
      "How much to penalize new tokens based on whether they appear in the text so far. Increases the model's likelihood to talk about new topics",
  },
};

const maxOutputTokensTooltip = (min: number, reasoningNoun: string) =>
  `The maximum number of tokens the model can generate in its response. The prompt does not count toward it. On ${reasoningNoun} models, ${reasoningNoun} tokens count toward it too, so a low limit can leave the response empty. The exact limit varies by model. The minimum is ${min}. (One token is roughly 4 characters for standard English text).`;

interface GenericModelConfigsProps {
  model?: string;
  params: ModelParams;
  presentation: GenericPanelPresentation;
  configs: Record<string, unknown>;
  onChange: (configs: Record<string, unknown>) => void;
  unsupportedParams?: ReadonlySet<ModelConfigParam>;
}

const GenericModelConfigs = ({
  model = "",
  params,
  presentation,
  configs,
  onChange,
  unsupportedParams,
}: GenericModelConfigsProps) => {
  const supports = createSupports(unsupportedParams);
  const controls = getShownControls(
    params,
    presentation,
    configs,
    unsupportedParams,
    model,
  );
  const shown = (control: ParamControl) =>
    getShownValue(control, params, presentation, configs, model);
  const samplingOffNote = params.temperature?.accepted
    ? presentation.getSamplingOffNote(
        model,
        configs,
        supports("topP") && params.top_p?.accepted === true,
      )
    : undefined;

  const renderNumber = (control: NumberControl) => {
    if (control.param === "max_output_tokens") {
      const value = shown(control) as number;
      const noLimitAtZero = presentation.zeroMeansNoLimit === true;
      return (
        <SliderInputControl
          key={control.configKey}
          value={value}
          onChange={(v) => onChange({ [control.configKey]: v })}
          id={presentation.maxOutputTokensId ?? control.configKey}
          min={noLimitAtZero ? 0 : control.min}
          max={control.max}
          step={1}
          integer
          hint={noLimitAtZero ? getNoOutputLimitHint(value) : undefined}
          defaultValue={control.default}
          label="Max output tokens"
          tooltip={
            <PromptModelConfigsTooltipContent
              text={
                noLimitAtZero
                  ? `${maxOutputTokensTooltip(
                      control.min,
                      presentation.reasoningNoun,
                    )} ${NO_OUTPUT_LIMIT_TOOLTIP}`
                  : maxOutputTokensTooltip(
                      control.min,
                      presentation.reasoningNoun,
                    )
              }
            />
          }
        />
      );
    }
    const slider = SLIDERS[control.param];
    return (
      <SliderInputControl
        key={control.configKey}
        value={shown(control) as number | undefined}
        onChange={(v) => onChange({ [control.configKey]: v })}
        id={control.configKey}
        min={control.min}
        max={control.max}
        step={0.01}
        defaultValue={control.default}
        label={slider.label}
        tooltip={<PromptModelConfigsTooltipContent text={slider.tooltip} />}
      />
    );
  };

  const renderSamplingChoice = (control: SamplingChoiceControl) => {
    const sampling = shown(control) as {
      temperature?: number;
      topP?: number;
    };
    return (
      <ExclusiveSamplingParams
        key="sampling"
        temperature={sampling.temperature}
        topP={sampling.topP}
        temperatureDefault={control.temperature.default}
        topPDefault={control.topP?.default ?? control.temperature.default}
        temperatureMin={control.temperature.min}
        temperatureMax={control.temperature.max}
        offerChoice={control.topP !== undefined}
        onChange={({ temperature, topP }) =>
          onChange({
            [control.temperature.configKey]: temperature,
            ...(control.topP && { [control.topP.configKey]: topP }),
          })
        }
      />
    );
  };

  const renderEffort = (control: EffortControl) => (
    <div key={control.configKey} className="space-y-2">
      <div className="flex items-center space-x-2">
        <Label htmlFor={control.configKey} className="text-sm font-medium">
          {presentation.getEffortTitle(model)}
        </Label>
        <ExplainerIcon description={presentation.getEffortDescription(model)} />
      </div>
      <SelectBox
        id={control.configKey}
        value={shown(control) as string}
        onChange={(value: string) => onChange({ [control.configKey]: value })}
        options={control.levels.map((level) => ({
          label: presentation.getEffortLabel(level),
          value: level,
        }))}
        placeholder={presentation.effortPlaceholder}
      />
    </div>
  );

  const renderControl = (control: ParamControl) => {
    switch (control.kind) {
      case "sampling_choice":
        return renderSamplingChoice(control);
      case "number":
        return renderNumber(control);
      case "effort":
        return renderEffort(control);
    }
  };

  return (
    <div className="flex w-72 flex-col gap-6">
      {controls.map(renderControl)}

      {samplingOffNote && (
        <p className="comet-body-xs text-muted-slate">{samplingOffNote}</p>
      )}

      {supports("throttling") && (
        <SliderInputControl
          value={
            (configs.throttling as number | undefined) ??
            presentation.throttlingDefault
          }
          onChange={(v) => onChange({ throttling: v })}
          id="throttling"
          min={0}
          max={10}
          step={0.1}
          defaultValue={presentation.throttlingDefault}
          label="Throttling (seconds)"
          tooltip={
            <PromptModelConfigsTooltipContent text="Minimum time in seconds between consecutive requests to avoid rate limiting" />
          }
        />
      )}

      {supports("maxConcurrentRequests") && (
        <SliderInputControl
          value={
            (configs.maxConcurrentRequests as number | undefined) ??
            presentation.maxConcurrentRequestsDefault
          }
          onChange={(v) => onChange({ maxConcurrentRequests: v })}
          id="maxConcurrentRequests"
          min={1}
          max={20}
          step={1}
          defaultValue={presentation.maxConcurrentRequestsDefault}
          label="Max concurrent requests"
          tooltip={
            <PromptModelConfigsTooltipContent text="Maximum number of requests that can run simultaneously. Set to 1 for sequential execution, higher values for parallel processing" />
          }
        />
      )}
    </div>
  );
};

export default GenericModelConfigs;
