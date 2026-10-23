import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";

import OpenRouterModelConfigs from "./OpenRouterModelConfigs";
import {
  LLMOpenRouterConfigsType,
  PROVIDER_MODEL_TYPE,
} from "@/types/providers";
import { TooltipProvider } from "@/ui/tooltip";
import {
  resetModelRegistryStoreForTesting,
  setLatestModelFlags,
} from "@/lib/modelRegistryStore";
import { resolveSamplingParams } from "@/lib/modelUtils";

const CONFIG: LLMOpenRouterConfigsType = {
  maxTokens: 0,
  temperature: 1.5,
  topP: 1,
  topK: 0,
  frequencyPenalty: 0,
  presencePenalty: 0,
  repetitionPenalty: 1,
  minP: 0,
  topA: 0,
};

const SAMPLING = ["max_tokens", "temperature", "top_p"];

const renderPanel = (model: PROVIDER_MODEL_TYPE) => {
  const onChange = vi.fn();
  render(
    <TooltipProvider delayDuration={700}>
      <OpenRouterModelConfigs
        configs={CONFIG}
        model={model}
        onChange={onChange}
      />
    </TooltipProvider>,
  );
  return onChange;
};

const typeAndBlur = (id: string, value: string) => {
  const input = screen.getByTestId(`${id}-input`);
  fireEvent.change(input, { target: { value } });
  fireEvent.blur(input);
};

// z-ai/glm-4.5 is served only by Z.AI, which takes temperature 0 to 1; the catalog says so.
describe("an OpenRouter model's temperature cap from the catalog", () => {
  beforeEach(() => {
    setLatestModelFlags(
      new Map([
        [
          PROVIDER_MODEL_TYPE.Z_AI_GLM_4_5,
          {
            reasoning: false,
            structuredOutput: true,
            supportedParameters: SAMPLING,
            temperatureMax: 1,
          },
        ],
        [
          PROVIDER_MODEL_TYPE.Z_AI_GLM_4_6,
          {
            reasoning: false,
            structuredOutput: true,
            supportedParameters: SAMPLING,
          },
        ],
      ]),
    );
  });

  afterEach(() => {
    resetModelRegistryStoreForTesting();
  });

  it("sends a stored temperature above the cap at the cap, and keeps it stored", () => {
    expect(
      resolveSamplingParams(PROVIDER_MODEL_TYPE.Z_AI_GLM_4_5, CONFIG)
        .temperature,
    ).toBe(1);
    expect(
      resolveSamplingParams(PROVIDER_MODEL_TYPE.Z_AI_GLM_4_6, CONFIG)
        .temperature,
    ).toBe(1.5);
  });

  it("shows the capped value and lets the slider go no higher", () => {
    const onChange = renderPanel(PROVIDER_MODEL_TYPE.Z_AI_GLM_4_5);

    expect(screen.getByTestId("temperature-input")).toHaveValue("1");

    typeAndBlur("temperature", "2");

    expect(onChange).toHaveBeenCalledWith({ temperature: 1 });
  });

  it("keeps the usual range of 2 for a model the catalog gives no cap", () => {
    const onChange = renderPanel(PROVIDER_MODEL_TYPE.Z_AI_GLM_4_6);

    typeAndBlur("temperature", "2");

    expect(onChange).toHaveBeenCalledWith({ temperature: 2 });
  });
});
