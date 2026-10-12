import { describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";

vi.mock("@uiw/react-codemirror", () => ({
  default: () => <div data-testid="codemirror-stub" />,
}));

import AnthropicModelConfigs from "./AnthropicModelConfigs";
import CustomModelConfig from "./CustomModelConfig";
import GeminiModelConfigs from "./GeminiModelConfigs";
import OpenAIModelConfigs from "./OpenAIModelConfigs";
import OpenRouterModelConfigs from "./OpenRouterModelConfigs";
import VertexAIModelConfigs from "./VertexAIModelConfigs";
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

const maxTokensInput = (id = "maxCompletionTokens") =>
  screen.getByTestId(`${id}-input`);

const typeMaxTokens = (value: string, id?: string) => {
  fireEvent.change(maxTokensInput(id), { target: { value } });
  fireEvent.blur(maxTokensInput(id));
};

describe("OpenAI max output tokens", () => {
  it.each([
    [PROVIDER_MODEL_TYPE.GPT_4O_MINI, undefined, "100000", 16384],
    [PROVIDER_MODEL_TYPE.GPT_4O_MINI, undefined, "0", 1],
    [PROVIDER_MODEL_TYPE.GPT_4O_MINI, undefined, "0.5", 1],
    [PROVIDER_MODEL_TYPE.GPT_4O_MINI, undefined, "20.5", 20],
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

  it("shows a stored 0 on a model capped below the default as that cap", () => {
    renderOpenAI("computer-use-preview" as PROVIDER_MODEL_TYPE, 0);

    expect(maxTokensInput()).toHaveValue("1024");
  });
});

describe("Anthropic max output tokens", () => {
  it.each([
    [PROVIDER_MODEL_TYPE.CLAUDE_HAIKU_4_5, "0", 1],
    [PROVIDER_MODEL_TYPE.CLAUDE_HAIKU_4_5, "20.5", 20],
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

describe("OpenRouter max output tokens", () => {
  const renderOpenRouter = (model: PROVIDER_MODEL_TYPE, maxTokens: number) =>
    render(
      <TooltipProvider>
        <OpenRouterModelConfigs
          configs={{
            maxTokens,
            temperature: 1,
            topP: 1,
            topK: 0,
            frequencyPenalty: 0,
            presencePenalty: 0,
            repetitionPenalty: 1,
            minP: 0,
            topA: 0,
          }}
          model={model}
          onChange={vi.fn()}
        />
      </TooltipProvider>,
    );

  it.each([
    [PROVIDER_MODEL_TYPE.GOOGLE_GEMMA_2_27B_IT, 4000, "2048"],
    [PROVIDER_MODEL_TYPE.GOOGLE_GEMMA_2_27B_IT, 1000, "1000"],
    [PROVIDER_MODEL_TYPE.GOOGLE_GEMMA_2_27B_IT, 0, "0"],
    [PROVIDER_MODEL_TYPE.OPENAI_GPT_4O_MINI, 4000, "4000"],
  ] as const)(
    "on %s shows a stored %d as %s, within what OpenRouter lists for it",
    (model, stored, shown) => {
      renderOpenRouter(model, stored);

      expect(maxTokensInput("maxTokens")).toHaveValue(shown);
    },
  );
});

describe("max output tokens where 0 means no limit", () => {
  const PANELS = [
    {
      name: "Gemini",
      id: "maxOutputTokens",
      key: "maxCompletionTokens",
      render: (maxCompletionTokens: number, onChange = vi.fn()) => (
        <GeminiModelConfigs
          configs={{ temperature: 0, maxCompletionTokens, topP: 1 }}
          model={PROVIDER_MODEL_TYPE.GEMINI_2_5_FLASH}
          onChange={onChange}
        />
      ),
    },
    {
      name: "Vertex AI",
      id: "maxOutputTokens",
      key: "maxCompletionTokens",
      render: (maxCompletionTokens: number, onChange = vi.fn()) => (
        <VertexAIModelConfigs
          configs={{ temperature: 0, maxCompletionTokens, topP: 1 }}
          model={PROVIDER_MODEL_TYPE.VERTEX_AI_GEMINI_2_5_FLASH}
          onChange={onChange}
        />
      ),
    },
    {
      name: "OpenRouter",
      id: "maxTokens",
      key: "maxTokens",
      render: (maxTokens: number, onChange = vi.fn()) => (
        <OpenRouterModelConfigs
          configs={{
            maxTokens,
            temperature: 1,
            topP: 1,
            topK: 0,
            frequencyPenalty: 0,
            presencePenalty: 0,
            repetitionPenalty: 1,
            minP: 0,
            topA: 0,
          }}
          model={PROVIDER_MODEL_TYPE.OPENAI_GPT_4O_MINI}
          onChange={onChange}
        />
      ),
    },
    {
      name: "Custom LLM",
      id: "maxCompletionTokens",
      key: "maxCompletionTokens",
      render: (maxCompletionTokens: number, onChange = vi.fn()) => (
        <CustomModelConfig
          configs={{ temperature: 0, maxCompletionTokens, topP: 1 }}
          model={"custom-llm/mock/mock-model" as PROVIDER_MODEL_TYPE}
          onChange={onChange}
        />
      ),
    },
  ];

  describe.each(PANELS)("$name", ({ id, key, render: renderPanel }) => {
    const renderWith = (value: number, onChange = vi.fn()) =>
      render(<TooltipProvider>{renderPanel(value, onChange)}</TooltipProvider>);

    it.each([
      ["0", 0, "0"],
      ["0.5", 0, "0"],
      ["20.5", 20, "20"],
      ["77", 77, "77"],
    ])("saves a typed %s as %d and shows %s", (typed, saved, shown) => {
      const onChange = vi.fn();
      renderWith(4000, onChange);

      typeMaxTokens(typed, id);

      expect(onChange).toHaveBeenCalledWith({ [key]: saved });
      expect(maxTokensInput(id)).toHaveValue(shown);
    });

    it.each([
      [0, true],
      [0.5, true],
      [1, false],
      [4000, false],
    ])("with %s stored, shows the no-limit hint: %s", (stored, hinted) => {
      renderWith(stored);

      expect(
        screen.queryByText("No limit: the provider's own default applies."),
      ).toEqual(hinted ? expect.anything() : null);
    });

    it("shows a stored fraction as the whole number it sends", () => {
      renderWith(20.5);

      expect(maxTokensInput(id)).toHaveValue("20");
    });
  });
});
