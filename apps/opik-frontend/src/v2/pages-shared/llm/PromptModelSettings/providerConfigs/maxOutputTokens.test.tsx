import { describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";

import AnthropicModelConfigs from "./AnthropicModelConfigs";
import OpenAIModelConfigs from "./OpenAIModelConfigs";
import { OpenAiPipelineMode, PROVIDER_MODEL_TYPE } from "@/types/providers";
import { TooltipProvider } from "@/ui/tooltip";

const renderOpenAI = (
  model: PROVIDER_MODEL_TYPE,
  maxCompletionTokens: number,
  openAiPipelineMode?: OpenAiPipelineMode,
  onChange = vi.fn(),
) =>
  render(
    <TooltipProvider>
      <OpenAIModelConfigs
        configs={{ temperature: 0, maxCompletionTokens }}
        model={model}
        onChange={onChange}
        openAiPipelineMode={openAiPipelineMode}
      />
    </TooltipProvider>,
  );

const renderAnthropic = (
  model: PROVIDER_MODEL_TYPE,
  maxCompletionTokens: number,
  onChange = vi.fn(),
) =>
  render(
    <TooltipProvider>
      <AnthropicModelConfigs
        configs={{ temperature: 0, maxCompletionTokens }}
        model={model}
        onChange={onChange}
      />
    </TooltipProvider>,
  );

const maxTokensInput = () => screen.getByTestId("maxCompletionTokens-input");

const typeMaxTokens = (value: string) => {
  fireEvent.change(maxTokensInput(), { target: { value } });
  fireEvent.blur(maxTokensInput());
};

describe("OpenAI max output tokens", () => {
  it.each([
    [PROVIDER_MODEL_TYPE.GPT_4O_MINI, undefined, "100000", 16384],
    [PROVIDER_MODEL_TYPE.GPT_4O_MINI, undefined, "0", 1],
    [PROVIDER_MODEL_TYPE.GPT_4O_MINI, "chat_completions_api", "5", 5],
    [PROVIDER_MODEL_TYPE.GPT_4O_MINI, "responses_api", "5", 16],
    [PROVIDER_MODEL_TYPE.GPT_4, undefined, "128000", 4096],
    [PROVIDER_MODEL_TYPE.GPT_5_NANO, undefined, "128000", 128000],
  ] as const)(
    "on %s with %s saves %s as %d",
    (model, openAiPipelineMode, typed, saved) => {
      const onChange = vi.fn();
      renderOpenAI(model, 4000, openAiPipelineMode, onChange);

      typeMaxTokens(typed);

      expect(onChange).toHaveBeenCalledWith({ maxCompletionTokens: saved });
    },
  );

  it("shows a stored value above the model limit as the limit it will send", () => {
    renderOpenAI(PROVIDER_MODEL_TYPE.GPT_4O_MINI, 100000);

    expect(maxTokensInput()).toHaveValue("16384");
  });

  it("shows a stored 0 as the default it will send", () => {
    renderOpenAI(PROVIDER_MODEL_TYPE.GPT_4O_MINI, 0);

    expect(maxTokensInput()).toHaveValue("4000");
  });
});

describe("Anthropic max output tokens", () => {
  it.each([
    [PROVIDER_MODEL_TYPE.CLAUDE_HAIKU_4_5, "0", 1],
    [PROVIDER_MODEL_TYPE.CLAUDE_HAIKU_4_5, "100000", 64000],
    [PROVIDER_MODEL_TYPE.CLAUDE_OPUS_4_6, "100000", 100000],
    [PROVIDER_MODEL_TYPE.CLAUDE_OPUS_4_6, "200000", 128000],
  ])("on %s saves %s as %d", (model, typed, saved) => {
    const onChange = vi.fn();
    renderAnthropic(model, 4000, onChange);

    typeMaxTokens(typed);

    expect(onChange).toHaveBeenCalledWith({ maxCompletionTokens: saved });
  });

  it("shows a stored 0 as the default it will send", () => {
    renderAnthropic(PROVIDER_MODEL_TYPE.CLAUDE_HAIKU_4_5, 0);

    expect(maxTokensInput()).toHaveValue("4000");
  });
});
