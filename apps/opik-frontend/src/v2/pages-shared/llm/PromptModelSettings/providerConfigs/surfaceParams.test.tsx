import { describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";

import AnthropicModelConfigs from "./AnthropicModelConfigs";
import OpenAIModelConfigs from "./OpenAIModelConfigs";
import {
  OPTIMIZATION_UNSUPPORTED_PARAMS,
  RULE_UNSUPPORTED_PARAMS,
} from "@/v2/pages-shared/llm/PromptModelSettings/modelConfigParams";
import {
  LLMAnthropicConfigsType,
  LLMOpenAIConfigsType,
  PROVIDER_MODEL_TYPE,
} from "@/types/providers";
import { TooltipProvider } from "@/ui/tooltip";

const renderPanel = (ui: React.ReactElement) =>
  render(<TooltipProvider delayDuration={700}>{ui}</TooltipProvider>);

const ANTHROPIC_CONFIG: LLMAnthropicConfigsType = {
  temperature: 0.4,
  maxCompletionTokens: 4000,
  throttling: 0,
  maxConcurrentRequests: 5,
};

const OPEN_AI_CONFIG: LLMOpenAIConfigsType = {
  temperature: 0.4,
  maxCompletionTokens: 4000,
  topP: 1,
  frequencyPenalty: 0,
  presencePenalty: 0,
  throttling: 0,
  maxConcurrentRequests: 5,
};

// Throttling and Max concurrent requests drive the playground's own batch runner and nothing else;
// an evaluator rule stores only LlmAsJudgeModelParameters (name, temperature, seed,
// custom_parameters), so every other control it used to render was discarded on save.
describe("controls an evaluator rule cannot store", () => {
  it("drops them from the Anthropic panel", () => {
    renderPanel(
      <AnthropicModelConfigs
        configs={ANTHROPIC_CONFIG}
        model={PROVIDER_MODEL_TYPE.CLAUDE_SONNET_4_6}
        onChange={vi.fn()}
        unsupportedParams={RULE_UNSUPPORTED_PARAMS}
      />,
    );

    expect(screen.getByTestId("temperature-input")).toBeInTheDocument();
    expect(screen.queryByTestId("topP-input")).not.toBeInTheDocument();
    expect(screen.queryByTestId("throttling-input")).not.toBeInTheDocument();
    expect(
      screen.queryByTestId("maxConcurrentRequests-input"),
    ).not.toBeInTheDocument();
    expect(screen.queryByText("Thinking effort")).not.toBeInTheDocument();
  });

  it("drops them from the OpenAI panel", () => {
    renderPanel(
      <OpenAIModelConfigs
        configs={OPEN_AI_CONFIG}
        model={PROVIDER_MODEL_TYPE.GPT_5_4}
        onChange={vi.fn()}
        unsupportedParams={RULE_UNSUPPORTED_PARAMS}
      />,
    );

    expect(screen.queryByTestId("throttling-input")).not.toBeInTheDocument();
    expect(
      screen.queryByTestId("maxConcurrentRequests-input"),
    ).not.toBeInTheDocument();
    expect(screen.queryByText("Reasoning effort")).not.toBeInTheDocument();
  });
});

describe("the playground and the optimizer", () => {
  it("keeps every control on the playground, which stores them all", () => {
    renderPanel(
      <AnthropicModelConfigs
        configs={ANTHROPIC_CONFIG}
        model={PROVIDER_MODEL_TYPE.CLAUDE_SONNET_4_6}
        onChange={vi.fn()}
      />,
    );

    expect(screen.getByTestId("throttling-input")).toBeInTheDocument();
    expect(
      screen.getByTestId("maxConcurrentRequests-input"),
    ).toBeInTheDocument();
    expect(screen.getByText("Thinking effort")).toBeInTheDocument();
  });

  it("keeps the effort control for the optimizer but drops the runner ones", () => {
    // The optimizer takes model parameters but does its own scheduling — throttling and max
    // concurrency are the playground runner's, and it forwards them to nothing.
    renderPanel(
      <OpenAIModelConfigs
        configs={OPEN_AI_CONFIG}
        model={PROVIDER_MODEL_TYPE.GPT_5_4}
        onChange={vi.fn()}
        unsupportedParams={OPTIMIZATION_UNSUPPORTED_PARAMS}
      />,
    );

    expect(screen.getByText("Reasoning effort")).toBeInTheDocument();
    expect(screen.queryByTestId("throttling-input")).not.toBeInTheDocument();
  });
});
