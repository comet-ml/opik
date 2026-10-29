import { describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";

// The real CodeMirror editor does not mount under this environment (a duplicate @codemirror/state breaks its
// instanceof checks), so a textarea stands in for it.
vi.mock("@uiw/react-codemirror", () => ({
  default: ({
    value,
    onChange,
    onBlur,
  }: {
    value: string;
    onChange: (value: string) => void;
    onBlur: () => void;
  }) => (
    <textarea
      data-testid="extra-body-editor"
      value={value}
      onChange={(event) => onChange(event.target.value)}
      onBlur={onBlur}
    />
  ),
}));

import CustomModelConfigs from "./CustomModelConfig";
import {
  LLMCustomConfigsType,
  PROVIDER_MODEL_TYPE,
  PROVIDER_TYPE,
} from "@/types/providers";
import { TooltipProvider } from "@/ui/tooltip";
import { RULE_UNSUPPORTED_PARAMS } from "@/v2/pages-shared/llm/PromptModelSettings/modelConfigParams";

const MODEL = "custom-llm/mock/mock-model" as PROVIDER_MODEL_TYPE;
const CLAUDE_MODEL =
  "custom-llm/gw/us.anthropic.claude-haiku-4-5-20251001-v1:0" as PROVIDER_MODEL_TYPE;

const renderPanel = (
  customParameters: Record<string, unknown> | null,
  {
    provider = PROVIDER_TYPE.CUSTOM,
    model = MODEL,
    sampling = { temperature: 0.25, topP: 1 },
    sliders,
    unsupportedParams,
  }: {
    provider?: PROVIDER_TYPE;
    model?: PROVIDER_MODEL_TYPE;
    sampling?: { temperature?: number; topP?: number };
    sliders?: Partial<LLMCustomConfigsType>;
    unsupportedParams?: typeof RULE_UNSUPPORTED_PARAMS;
  } = {},
) =>
  render(
    <TooltipProvider delayDuration={700}>
      <CustomModelConfigs
        configs={
          {
            ...sampling,
            maxCompletionTokens: 66,
            frequencyPenalty: 0,
            presencePenalty: 0,
            ...sliders,
            custom_parameters: customParameters,
          } as LLMCustomConfigsType
        }
        model={model}
        provider={provider}
        unsupportedParams={unsupportedParams}
        onChange={vi.fn()}
      />
    </TooltipProvider>,
  );

const typeInvalidJson = () => {
  const editor = screen.getByTestId("extra-body-editor");
  fireEvent.change(editor, { target: { value: '{"top_k": 7,' } });
  fireEvent.blur(editor);
};

describe("CustomModelConfig extra body", () => {
  it.each([
    [{ temperature: 0.95, top_k: 7 }, "temperature"],
    [
      { max_completion_tokens: 12, top_p: 0.5, temperature: 0.95 },
      "temperature, top_p, max_completion_tokens",
    ],
  ])("names the slider keys %j sends instead", (customParameters, keys) => {
    renderPanel(customParameters);

    expect(
      screen.getByText(`Sent instead of the slider: ${keys}`),
    ).toBeInTheDocument();
  });

  it.each([
    [
      PROVIDER_TYPE.OLLAMA,
      { max_completion_tokens: 12 },
      "max_completion_tokens (as max_tokens)",
    ],
    [PROVIDER_TYPE.OLLAMA, { max_tokens: 12 }, "max_tokens"],
    [
      PROVIDER_TYPE.OLLAMA,
      { max_tokens: 12, max_completion_tokens: 30 },
      "max_tokens",
    ],
    [
      PROVIDER_TYPE.BEDROCK,
      { max_tokens: 40 },
      "max_tokens (as max_completion_tokens)",
    ],
    [
      PROVIDER_TYPE.BEDROCK,
      { max_completion_tokens: 40 },
      "max_completion_tokens",
    ],
    [PROVIDER_TYPE.CUSTOM, { max_tokens: 12 }, "max_tokens"],
    [
      PROVIDER_TYPE.CUSTOM,
      { max_tokens: 12, max_completion_tokens: 30 },
      "max_tokens, max_completion_tokens",
    ],
  ])(
    "on %s names the token limit %j sends as %s",
    (provider, customParameters, keys) => {
      renderPanel(customParameters, { provider });

      expect(
        screen.getByText(`Sent instead of the slider: ${keys}`),
      ).toBeInTheDocument();
    },
  );

  it.each([
    {
      name: "Ollama sends no Max output tokens of 0",
      provider: PROVIDER_TYPE.OLLAMA,
      sliders: { maxCompletionTokens: 0 },
      customParameters: { max_tokens: 12 },
      expected: null,
    },
    {
      name: "Bedrock sends no Max output tokens of 0",
      provider: PROVIDER_TYPE.BEDROCK,
      sliders: { maxCompletionTokens: 0 },
      customParameters: { max_completion_tokens: 40 },
      expected: null,
    },
    {
      name: "Bedrock sends no penalty of 0",
      provider: PROVIDER_TYPE.BEDROCK,
      sliders: { frequencyPenalty: 0, presencePenalty: 0 },
      customParameters: { frequency_penalty: 0.3, presence_penalty: 0.4 },
      expected: null,
    },
    {
      name: "Bedrock sends a penalty that is not 0",
      provider: PROVIDER_TYPE.BEDROCK,
      sliders: { frequencyPenalty: 0.5, presencePenalty: 0 },
      customParameters: { frequency_penalty: 0.3, presence_penalty: 0.4 },
      expected: "frequency_penalty",
    },
    {
      name: "Ollama sends a penalty of 0",
      provider: PROVIDER_TYPE.OLLAMA,
      sliders: { frequencyPenalty: 0 },
      customParameters: { frequency_penalty: 0.3 },
      expected: "frequency_penalty",
    },
    {
      name: "a Custom LLM sends no Max output tokens of 0",
      provider: PROVIDER_TYPE.CUSTOM,
      sliders: { maxCompletionTokens: 0 },
      customParameters: { max_tokens: 12 },
      expected: null,
    },
    {
      name: "a Max output tokens below 1 is rounded down to no limit",
      provider: PROVIDER_TYPE.CUSTOM,
      sliders: { maxCompletionTokens: 0.5 },
      customParameters: { max_tokens: 12 },
      expected: null,
    },
    {
      name: "a Custom LLM sends a Max output tokens of 1",
      provider: PROVIDER_TYPE.CUSTOM,
      sliders: { maxCompletionTokens: 1 },
      customParameters: { max_tokens: 12 },
      expected: "max_tokens",
    },
  ])(
    "names only the sliders the request sends: $name",
    ({ provider, sliders, customParameters, expected }) => {
      renderPanel(customParameters, { provider, sliders });

      expect(
        screen.queryByText(/Sent instead of the slider/)?.textContent ?? null,
      ).toBe(expected && `Sent instead of the slider: ${expected}`);
    },
  );

  it("names no token limit where the surface has no slider for it", () => {
    renderPanel(
      { max_tokens: 12 },
      { unsupportedParams: RULE_UNSUPPORTED_PARAMS },
    );

    expect(
      screen.queryByText(/Sent instead of the slider/),
    ).not.toBeInTheDocument();
  });

  it.each([
    [
      { temperature: 0.4 },
      { top_p: 0.5 },
      "top_p is sent next to temperature.",
    ],
    [{ topP: 0.6 }, { temperature: 0.5 }, "temperature is sent next to top_p."],
  ])(
    "on a Claude model with %j says %j is sent next to the live half",
    (sampling, customParameters, note) => {
      renderPanel(customParameters, { model: CLAUDE_MODEL, sampling });

      expect(
        screen.queryByText(/Sent instead of the slider/),
      ).not.toBeInTheDocument();
      expect(
        screen.getByText(`${note} Claude may reject the two together.`),
      ).toBeInTheDocument();
    },
  );

  it("on a Claude model says the extra body replaces the live half", () => {
    renderPanel(
      { top_p: 0.5 },
      { model: CLAUDE_MODEL, sampling: { topP: 0.6 } },
    );

    expect(
      screen.getByText("Sent instead of the slider: top_p"),
    ).toBeInTheDocument();
    expect(screen.queryByText(/is sent next to/)).not.toBeInTheDocument();
  });

  it.each([[{ top_k: 7 }], [null]])(
    "names no slider for an extra body of %j",
    (customParameters) => {
      renderPanel(customParameters);

      expect(
        screen.queryByText(/Sent instead of the slider/),
      ).not.toBeInTheDocument();
    },
  );

  it.each([
    [{ top_k: 7 }, "Invalid JSON, not saved. Runs keep the last valid JSON."],
    [null, "Invalid JSON, not saved."],
  ])(
    "says what runs send after invalid JSON over %j",
    (customParameters, message) => {
      renderPanel(customParameters);

      typeInvalidJson();

      expect(screen.getByText(message)).toBeInTheDocument();
    },
  );
});
