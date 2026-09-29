import { describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";

import AnthropicModelConfigs from "./AnthropicModelConfigs";
import GeminiModelConfigs from "./GeminiModelConfigs";
import OpenAIModelConfigs from "./OpenAIModelConfigs";
import VertexAIModelConfigs from "./VertexAIModelConfigs";
import {
  OPTIMIZATION_UNSUPPORTED_PARAMS,
  RULE_UNSUPPORTED_PARAMS,
} from "@/v2/pages-shared/llm/PromptModelSettings/modelConfigParams";
import {
  LLMAnthropicConfigsType,
  LLMGeminiConfigsType,
  LLMOpenAIConfigsType,
  LLMVertexAIConfigsType,
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
    // LlmAsJudgeModelParameters has no max-tokens field, so the converter drops whatever this
    // slider wrote. Anthropic is the one panel that renders it unconditionally rather than only
    // when the config carries the key, so it reached the rule form.
    expect(
      screen.queryByTestId("maxCompletionTokens-input"),
    ).not.toBeInTheDocument();
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
    expect(screen.queryByText("Thinking effort")).not.toBeInTheDocument();
    expect(screen.getByTestId("maxCompletionTokens-input")).toBeInTheDocument();
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

describe("a panel left with no control", () => {
  const EMPTY_LINE = "This model has no adjustable parameters here.";

  it("says so for a Claude model that takes no sampling params on a rule", () => {
    renderPanel(
      <AnthropicModelConfigs
        configs={ANTHROPIC_CONFIG}
        model={PROVIDER_MODEL_TYPE.CLAUDE_SONNET_5}
        onChange={vi.fn()}
        unsupportedParams={RULE_UNSUPPORTED_PARAMS}
      />,
    );

    expect(screen.getByText(EMPTY_LINE)).toBeInTheDocument();
  });

  it("says so for an OpenAI reasoning model on a rule", () => {
    // The OpenAI panel shows max output tokens whenever the config carries the key, and a rule's
    // config never does: it holds only temperature and seed.
    renderPanel(
      <OpenAIModelConfigs
        configs={{ temperature: 0.4 }}
        model={PROVIDER_MODEL_TYPE.GPT_6_ASTRA}
        onChange={vi.fn()}
        unsupportedParams={RULE_UNSUPPORTED_PARAMS}
      />,
    );

    expect(screen.getByText(EMPTY_LINE)).toBeInTheDocument();
  });

  // A rule's config holds only temperature and seed, which PromptModelConfigs casts to the full
  // type. A Gemini 3 model takes no sampling params, and one without a thinking-level row yet (newly
  // synced) has no level control either.
  const RULE_CONFIG = { temperature: 0.4 };

  it("says so for an unlisted Gemini 3 model on a rule", () => {
    renderPanel(
      <GeminiModelConfigs
        configs={RULE_CONFIG as LLMGeminiConfigsType}
        model={"gemini-3.9-flash" as PROVIDER_MODEL_TYPE}
        onChange={vi.fn()}
        unsupportedParams={RULE_UNSUPPORTED_PARAMS}
      />,
    );

    expect(screen.getByText(EMPTY_LINE)).toBeInTheDocument();
  });

  it("says so for an unlisted Vertex AI Gemini 3 model on a rule", () => {
    renderPanel(
      <VertexAIModelConfigs
        configs={RULE_CONFIG as LLMVertexAIConfigsType}
        model={"vertex_ai/gemini-3.9-flash" as PROVIDER_MODEL_TYPE}
        onChange={vi.fn()}
        unsupportedParams={RULE_UNSUPPORTED_PARAMS}
      />,
    );

    expect(screen.getByText(EMPTY_LINE)).toBeInTheDocument();
  });

  it("stays quiet for a Gemini 3 model that offers a thinking level", () => {
    renderPanel(
      <GeminiModelConfigs
        configs={RULE_CONFIG as LLMGeminiConfigsType}
        model={PROVIDER_MODEL_TYPE.GEMINI_3_5_FLASH}
        onChange={vi.fn()}
        unsupportedParams={RULE_UNSUPPORTED_PARAMS}
      />,
    );

    expect(screen.getByText("Thinking level")).toBeInTheDocument();
    expect(screen.queryByText(EMPTY_LINE)).not.toBeInTheDocument();
  });

  it("stays quiet on the playground, where the same Claude model keeps its other controls", () => {
    renderPanel(
      <AnthropicModelConfigs
        configs={ANTHROPIC_CONFIG}
        model={PROVIDER_MODEL_TYPE.CLAUDE_SONNET_5}
        onChange={vi.fn()}
      />,
    );

    expect(screen.getByTestId("maxCompletionTokens-input")).toBeInTheDocument();
    expect(screen.queryByText(EMPTY_LINE)).not.toBeInTheDocument();
  });
});
