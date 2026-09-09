import { describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";

import AnthropicModelConfigs from "./AnthropicModelConfigs";
import OpenAIModelConfigs from "./OpenAIModelConfigs";
import {
  LLMAnthropicConfigsType,
  LLMOpenAIConfigsType,
  PROVIDER_MODEL_TYPE,
} from "@/types/providers";
import { TooltipProvider } from "@/ui/tooltip";

const OPEN_AI_CONFIG: LLMOpenAIConfigsType = {
  temperature: 0,
  maxCompletionTokens: 4000,
  topP: 0.75,
  frequencyPenalty: 0,
  presencePenalty: 0,
};

const ANTHROPIC_WITHOUT_SAMPLING = {
  maxCompletionTokens: 4000,
} as LLMAnthropicConfigsType;

const renderPanel = (ui: React.ReactElement) =>
  render(<TooltipProvider delayDuration={700}>{ui}</TooltipProvider>);

describe("OpenAI sampling params", () => {
  it("offers Top P for a non-reasoning model", () => {
    renderPanel(
      <OpenAIModelConfigs
        configs={OPEN_AI_CONFIG}
        model={PROVIDER_MODEL_TYPE.GPT_4O_MINI}
        onChange={vi.fn()}
      />,
    );

    expect(screen.getByTestId("topP-input")).toHaveValue("0.75");
  });

  it("hides Top P for a reasoning model, which rejects the parameter", () => {
    renderPanel(
      <OpenAIModelConfigs
        configs={OPEN_AI_CONFIG}
        model={PROVIDER_MODEL_TYPE.GPT_5_5}
        onChange={vi.fn()}
      />,
    );

    expect(screen.queryByTestId("topP-input")).not.toBeInTheDocument();
  });
});

describe("surfaces whose config has no topP", () => {
  it("does not offer Top P to the LLM judge, whose rule cannot store it", () => {
    // The judge dialog renders this same panel over its own four-field config. Offering a slider
    // that convertLLMJudgeDataToLLMJudgeObject drops on save is the defect this file is about,
    // pointed the other way.
    renderPanel(
      <OpenAIModelConfigs
        configs={
          {
            temperature: 0,
            seed: null,
            custom_parameters: null,
          } as unknown as LLMOpenAIConfigsType
        }
        model={PROVIDER_MODEL_TYPE.GPT_4O_MINI}
        onChange={vi.fn()}
      />,
    );

    expect(screen.queryByTestId("topP-input")).not.toBeInTheDocument();
  });
});

describe("Anthropic sampling params", () => {
  it("shows temperature as the active half of the pair when a config lost both", () => {
    // Anthropic takes temperature or topP, never both. With neither set the panel used to enable
    // both sliders and send neither, so the displayed Temperature was not the one the model ran at.
    renderPanel(
      <AnthropicModelConfigs
        configs={ANTHROPIC_WITHOUT_SAMPLING}
        model={PROVIDER_MODEL_TYPE.CLAUDE_SONNET_4_6}
        onChange={vi.fn()}
      />,
    );

    expect(screen.getByTestId("temperature-input")).toHaveValue("0");
    expect(
      screen.getByLabelText("Clear temperature to use Top P"),
    ).toBeInTheDocument();
    expect(
      screen.queryByLabelText("Clear Top P to use temperature"),
    ).not.toBeInTheDocument();
  });

  it("still hides both for a model that rejects sampling params", () => {
    renderPanel(
      <AnthropicModelConfigs
        configs={{ ...ANTHROPIC_WITHOUT_SAMPLING, temperature: 0.7 }}
        model={PROVIDER_MODEL_TYPE.CLAUDE_SONNET_5}
        onChange={vi.fn()}
      />,
    );

    expect(screen.queryByTestId("temperature-input")).not.toBeInTheDocument();
    expect(screen.queryByTestId("topP-input")).not.toBeInTheDocument();
  });
});
