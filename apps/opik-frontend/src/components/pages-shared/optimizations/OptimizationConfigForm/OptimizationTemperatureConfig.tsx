import React from "react";
import { Settings2 } from "lucide-react";
import isUndefined from "lodash/isUndefined";

import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { Button, ButtonProps } from "@/components/ui/button";
import SliderInputControl from "@/components/shared/SliderInputControl/SliderInputControl";
import PromptModelConfigsTooltipContent from "@/components/pages-shared/llm/PromptModelSettings/providerConfigs/PromptModelConfigsTooltipContent";
import { LLMPromptConfigsType, PROVIDER_MODEL_TYPE } from "@/types/providers";
import { DEFAULT_OPEN_AI_CONFIGS } from "@/constants/llm";
import { isReasoningModel } from "@/lib/modelUtils";

interface OptimizationTemperatureConfigProps {
  model?: PROVIDER_MODEL_TYPE | "";
  size?: ButtonProps["size"];
  configs: Partial<LLMPromptConfigsType>;
  onChange: (configs: Partial<LLMPromptConfigsType>) => void;
  disabled?: boolean;
}

// @ToDo: remove when we support all params
const OptimizationTemperatureConfig: React.FC<
  OptimizationTemperatureConfigProps
> = ({ model, size = "icon-sm", configs, onChange, disabled = false }) => {
  const isReasoning = isReasoningModel(model);
  const hasTemperature = !isUndefined(configs.temperature);

  if (!hasTemperature) {
    return null;
  }

  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <Button variant="outline" size={size} disabled={disabled}>
          <Settings2 />
        </Button>
      </DropdownMenuTrigger>

      <DropdownMenuContent className="p-6" side="bottom" align="end">
        <div className="w-72">
          <SliderInputControl
            value={configs.temperature}
            onChange={(v) => onChange({ temperature: v })}
            id="temperature"
            min={isReasoning ? 1 : 0}
            max={isReasoning ? 1 : 2}
            step={0.01}
            defaultValue={isReasoning ? 1 : DEFAULT_OPEN_AI_CONFIGS.TEMPERATURE}
            label="Temperature"
            tooltip={
              <PromptModelConfigsTooltipContent
                text={
                  isReasoning
                    ? "Reasoning models require temperature = 1.0. This setting controls randomness in completions."
                    : "Controls randomness: Lowering results in less random completions. As the temperature approaches zero, the model will become deterministic and repetitive."
                }
              />
            }
          />
        </div>
      </DropdownMenuContent>
    </DropdownMenu>
  );
};

export default OptimizationTemperatureConfig;
