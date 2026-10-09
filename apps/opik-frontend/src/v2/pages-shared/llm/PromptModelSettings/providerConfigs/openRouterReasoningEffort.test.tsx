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

const CONFIG: LLMOpenRouterConfigsType = {
  maxTokens: 0,
  temperature: 0,
  topP: 1,
  topK: 0,
  frequencyPenalty: 0,
  presencePenalty: 0,
  repetitionPenalty: 1,
  minP: 0,
  topA: 0,
};

const renderPanel = (
  model: PROVIDER_MODEL_TYPE,
  configs: LLMOpenRouterConfigsType = CONFIG,
) => {
  const onChange = vi.fn();
  render(
    <TooltipProvider delayDuration={700}>
      <OpenRouterModelConfigs
        configs={configs}
        model={model}
        onChange={onChange}
      />
    </TooltipProvider>,
  );
  return onChange;
};

const openEffortDropdown = () => {
  fireEvent.keyDown(screen.getByRole("combobox"), { key: "Enter" });
  return screen.getAllByRole("option").map((option) => option.textContent);
};

describe("the OpenRouter reasoning effort dropdown", () => {
  beforeEach(() => {
    setLatestModelFlags(
      new Map([
        [
          PROVIDER_MODEL_TYPE.OPENAI_GPT_5_NANO,
          {
            reasoning: false,
            structuredOutput: false,
            supportedParameters: [
              "max_tokens",
              "reasoning",
              "reasoning_effort",
            ],
            reasoningEfforts: ["high", "medium", "low", "minimal"],
          },
        ],
        [
          PROVIDER_MODEL_TYPE.OPENAI_GPT_4O_MINI,
          {
            reasoning: false,
            structuredOutput: false,
            supportedParameters: ["max_tokens", "temperature", "top_p"],
          },
        ],
      ]),
    );
  });

  afterEach(() => {
    resetModelRegistryStoreForTesting();
  });

  it("starts at Default and offers the model's levels", () => {
    renderPanel(PROVIDER_MODEL_TYPE.OPENAI_GPT_5_NANO);

    expect(screen.getByRole("combobox")).toHaveTextContent("Default");
    expect(openEffortDropdown()).toEqual([
      "Default",
      "Minimal",
      "Low",
      "Medium",
      "High",
    ]);
  });

  it("stores a picked level", () => {
    const onChange = renderPanel(PROVIDER_MODEL_TYPE.OPENAI_GPT_5_NANO);

    openEffortDropdown();
    fireEvent.keyDown(screen.getByRole("option", { name: "Low" }), {
      key: "Enter",
    });

    expect(onChange).toHaveBeenCalledWith({ reasoningEffort: "low" });
  });

  it("clears the level when Default is picked", () => {
    const onChange = renderPanel(PROVIDER_MODEL_TYPE.OPENAI_GPT_5_NANO, {
      ...CONFIG,
      reasoningEffort: "low",
    });

    expect(screen.getByRole("combobox")).toHaveTextContent("Low");
    openEffortDropdown();
    fireEvent.keyDown(screen.getByRole("option", { name: "Default" }), {
      key: "Enter",
    });

    expect(onChange).toHaveBeenCalledWith({ reasoningEffort: undefined });
  });

  it("is not rendered for a model without reasoning", () => {
    renderPanel(PROVIDER_MODEL_TYPE.OPENAI_GPT_4O_MINI);

    expect(screen.queryByRole("combobox")).not.toBeInTheDocument();
    expect(screen.queryByText("Reasoning effort")).not.toBeInTheDocument();
  });
});
