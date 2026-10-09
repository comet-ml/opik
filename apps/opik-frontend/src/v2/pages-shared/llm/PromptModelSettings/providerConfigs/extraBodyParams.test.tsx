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
import { LLMCustomConfigsType, PROVIDER_MODEL_TYPE } from "@/types/providers";
import { TooltipProvider } from "@/ui/tooltip";

const MODEL = "custom-llm/mock/mock-model" as PROVIDER_MODEL_TYPE;

const renderPanel = (customParameters: Record<string, unknown> | null) =>
  render(
    <TooltipProvider delayDuration={700}>
      <CustomModelConfigs
        configs={
          {
            temperature: 0.25,
            maxCompletionTokens: 66,
            topP: 1,
            frequencyPenalty: 0,
            presencePenalty: 0,
            custom_parameters: customParameters,
          } as LLMCustomConfigsType
        }
        model={MODEL}
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
