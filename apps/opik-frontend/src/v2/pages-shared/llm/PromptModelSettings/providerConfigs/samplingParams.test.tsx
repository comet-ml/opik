import { describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";

import AnthropicModelConfigs from "./AnthropicModelConfigs";
import OpenAIModelConfigs from "./OpenAIModelConfigs";
import {
  LLMAnthropicConfigsType,
  LLMOpenAIConfigsType,
  PROVIDER_MODEL_TYPE,
} from "@/types/providers";
import { TooltipProvider } from "@/ui/tooltip";
import { RULE_UNSUPPORTED_PARAMS } from "@/v2/pages-shared/llm/PromptModelSettings/modelConfigParams";

const OPEN_AI_CONFIG: LLMOpenAIConfigsType = {
  temperature: 0,
  maxCompletionTokens: 4000,
  topP: 0.75,
  frequencyPenalty: 0,
  presencePenalty: 0,
};

const ANTHROPIC_CONFIG: LLMAnthropicConfigsType = {
  temperature: 0.4,
  maxCompletionTokens: 4000,
};

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

  it("hides both sliders for a reasoning model, which takes neither", () => {
    // top_p is rejected outright and temperature accepts only the provider's own default, so a
    // temperature slider here could only ever be a control clamped to a single value.
    renderPanel(
      <OpenAIModelConfigs
        configs={OPEN_AI_CONFIG}
        model={PROVIDER_MODEL_TYPE.GPT_5_5}
        onChange={vi.fn()}
      />,
    );

    expect(screen.queryByTestId("topP-input")).not.toBeInTheDocument();
    expect(screen.queryByTestId("temperature-input")).not.toBeInTheDocument();
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
  // Anthropic takes temperature or topP, never both. That used to be two sliders with
  // "Max output tokens" between them, one dimmed, each with its own ✕ button — the exclusivity was
  // only discoverable by trying it. One choice and one slider says the same thing outright.
  it("offers the pair as a choice with temperature live by default", () => {
    renderPanel(
      <AnthropicModelConfigs
        configs={ANTHROPIC_CONFIG}
        model={PROVIDER_MODEL_TYPE.CLAUDE_SONNET_4_6}
        onChange={vi.fn()}
      />,
    );

    expect(screen.getByRole("radio", { name: "Temperature" })).toBeChecked();
    expect(screen.getByRole("radio", { name: "Top P" })).not.toBeChecked();
    expect(screen.getByTestId("temperature-input")).toHaveValue("0.4");
    expect(screen.queryByTestId("topP-input")).not.toBeInTheDocument();
  });

  it("shows Top P as the live half when that is what the config carries", () => {
    renderPanel(
      <AnthropicModelConfigs
        configs={{ maxCompletionTokens: 4000, topP: 0.9 }}
        model={PROVIDER_MODEL_TYPE.CLAUDE_SONNET_4_6}
        onChange={vi.fn()}
      />,
    );

    expect(screen.getByRole("radio", { name: "Top P" })).toBeChecked();
    expect(screen.getByTestId("topP-input")).toHaveValue("0.9");
    expect(screen.queryByTestId("temperature-input")).not.toBeInTheDocument();
  });

  it("clears the other half when the choice changes, so the request carries one", () => {
    const onChange = vi.fn();
    renderPanel(
      <AnthropicModelConfigs
        configs={ANTHROPIC_CONFIG}
        model={PROVIDER_MODEL_TYPE.CLAUDE_SONNET_4_6}
        onChange={onChange}
      />,
    );

    fireEvent.click(screen.getByRole("radio", { name: "Top P" }));

    expect(onChange).toHaveBeenCalledWith({ topP: 1, temperature: undefined });
  });

  it("resolves a config that carries neither to a live temperature", () => {
    // A config that went through a model without sampling params comes back with neither half set.
    // Left unresolved the panel showed two live sliders and sent no sampling parameter at all.
    renderPanel(
      <AnthropicModelConfigs
        configs={{ maxCompletionTokens: 4000 }}
        model={PROVIDER_MODEL_TYPE.CLAUDE_SONNET_4_6}
        onChange={vi.fn()}
      />,
    );

    expect(screen.getByRole("radio", { name: "Temperature" })).toBeChecked();
    expect(screen.getByTestId("temperature-input")).toHaveValue("0");
  });

  it("hides the choice for a model that rejects sampling params", () => {
    renderPanel(
      <AnthropicModelConfigs
        configs={{ ...ANTHROPIC_CONFIG, temperature: 0.7 }}
        model={PROVIDER_MODEL_TYPE.CLAUDE_SONNET_5}
        onChange={vi.fn()}
      />,
    );

    expect(screen.queryByText("Sampling")).not.toBeInTheDocument();
    expect(screen.queryByTestId("temperature-input")).not.toBeInTheDocument();
    expect(screen.queryByTestId("topP-input")).not.toBeInTheDocument();
  });

  it("offers temperature alone where the surface cannot store a Top P", () => {
    renderPanel(
      <AnthropicModelConfigs
        configs={ANTHROPIC_CONFIG}
        model={PROVIDER_MODEL_TYPE.CLAUDE_SONNET_4_6}
        onChange={vi.fn()}
        unsupportedParams={RULE_UNSUPPORTED_PARAMS}
      />,
    );

    expect(
      screen.queryByRole("radio", { name: "Top P" }),
    ).not.toBeInTheDocument();
    expect(screen.getByTestId("temperature-input")).toHaveValue("0.4");
  });
});
