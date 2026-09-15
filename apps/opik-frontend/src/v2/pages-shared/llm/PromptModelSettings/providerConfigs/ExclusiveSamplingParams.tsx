import React, { useCallback } from "react";

import SliderInputControl from "@/shared/SliderInputControl/SliderInputControl";
import PromptModelConfigsTooltipContent from "@/v2/pages-shared/llm/PromptModelSettings/providerConfigs/PromptModelConfigsTooltipContent";
import { ToggleGroup, ToggleGroupItem } from "@/ui/toggle-group";
import { Label } from "@/ui/label";
import ExplainerIcon from "@/shared/ExplainerIcon/ExplainerIcon";
import isNil from "lodash/isNil";

interface ExclusiveSamplingParamsProps {
  temperature?: number;
  topP?: number;
  temperatureDefault: number;
  topPDefault: number;
  temperatureMin?: number;
  onChange: (configs: { temperature?: number; topP?: number }) => void;
  /**
   * False where the surface cannot store a Top P, which leaves temperature as the only option and
   * so nothing to choose between.
   */
  offerChoice?: boolean;
}

/**
 * The temperature-or-Top-P choice for Claude models, which reject the two together.
 *
 * Shared because the constraint belongs to the model rather than the provider routing it: the same
 * Claude models arrive through Anthropic, Bedrock, OpenRouter and OpenAI-compatible proxies, and a
 * panel that offered both would show a Top P the request drops.
 *
 * One slider, not two dimmed ones: the stored config holds whichever half is live, so the choice is
 * what switches them — handing the incoming parameter its default and clearing the outgoing one.
 */
const ExclusiveSamplingParams = ({
  temperature,
  topP,
  temperatureDefault,
  topPDefault,
  temperatureMin = 0,
  onChange,
  offerChoice = true,
}: ExclusiveSamplingParamsProps) => {
  const topPLive = !isNil(topP) && offerChoice;

  const handleTemperatureChange = useCallback(
    (v: number) => onChange({ temperature: v, topP: undefined }),
    [onChange],
  );

  const handleTopPChange = useCallback(
    (v: number) => onChange({ topP: v, temperature: undefined }),
    [onChange],
  );

  const handleChoiceChange = useCallback(
    (value: string) => {
      if (value === "topP") {
        onChange({ topP: topPDefault, temperature: undefined });
      } else if (value === "temperature") {
        onChange({ temperature: temperatureDefault, topP: undefined });
      }
    },
    [onChange, temperatureDefault, topPDefault],
  );

  return (
    <div className="space-y-2">
      {offerChoice && (
        <>
          <div className="flex items-center space-x-2">
            <Label className="text-sm font-medium">Sampling</Label>
            <ExplainerIcon description="Claude models take either Temperature or Top P, not both. Pick the one you want to tune." />
          </div>
          <ToggleGroup
            type="single"
            variant="secondary"
            value={topPLive ? "topP" : "temperature"}
            onValueChange={handleChoiceChange}
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
          defaultValue={topPDefault}
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
          min={temperatureMin}
          max={1}
          step={0.01}
          defaultValue={temperatureDefault}
          label="Temperature"
          tooltip={
            <PromptModelConfigsTooltipContent text="Controls randomness: Lowering results in less random completions. As the temperature approaches zero, the model will become deterministic and repetitive." />
          }
        />
      )}
    </div>
  );
};

export default ExclusiveSamplingParams;
