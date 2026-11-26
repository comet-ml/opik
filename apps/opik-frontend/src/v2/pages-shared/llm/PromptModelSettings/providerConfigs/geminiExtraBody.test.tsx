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

import GeminiExtraBodyParameters from "./GeminiExtraBodyParameters";
import GeminiModelConfigs from "./GeminiModelConfigs";
import VertexAIModelConfigs from "./VertexAIModelConfigs";
import {
  LLMGeminiConfigsType,
  PROVIDER_MODEL_TYPE,
  PROVIDER_TYPE,
} from "@/types/providers";
import { TooltipProvider } from "@/ui/tooltip";
import {
  BACKEND_RUN_UNSUPPORTED_PARAMS,
  OPTIMIZATION_UNSUPPORTED_PARAMS,
  RULE_UNSUPPORTED_PARAMS,
} from "@/v2/pages-shared/llm/PromptModelSettings/modelConfigParams";

const UNLISTED_GEMINI = "gemini-9-unlisted" as PROVIDER_MODEL_TYPE;
const UNLISTED_VERTEX = "vertex_ai/gemini-9-unlisted" as PROVIDER_MODEL_TYPE;

const CONFIG: LLMGeminiConfigsType = {
  temperature: 1,
  maxCompletionTokens: 0,
  topP: 1,
};

const renderBox = (customParameters: Record<string, unknown> | null) => {
  const onChange = vi.fn();
  render(
    <TooltipProvider delayDuration={700}>
      <GeminiExtraBodyParameters
        provider={PROVIDER_TYPE.GEMINI}
        customParameters={customParameters}
        onChange={onChange}
      />
    </TooltipProvider>,
  );
  return { onChange, editor: screen.getByTestId("extra-body-editor") };
};

const type = (editor: HTMLElement, value: string) => {
  fireEvent.change(editor, { target: { value } });
  fireEvent.blur(editor);
};

describe("the Gemini extra body box", () => {
  it("shows the custom keys but not the thinking level Opik keeps there", () => {
    const { editor } = renderBox({ topK: 40, thinking: { level: "low" } });

    expect(JSON.parse((editor as HTMLTextAreaElement).value)).toEqual({
      topK: 40,
    });
  });

  it("keeps the thinking level when the custom keys change", () => {
    const { onChange, editor } = renderBox({ thinking: { level: "low" } });

    type(editor, '{"topK": 40, "seed": 7}');

    expect(onChange).toHaveBeenCalledWith({
      custom_parameters: { topK: 40, seed: 7, thinking: { level: "low" } },
    });
  });

  it("keeps only the thinking level when the box is cleared", () => {
    const { onChange, editor } = renderBox({
      topK: 40,
      thinking: { level: "low" },
    });

    type(editor, "");

    expect(onChange).toHaveBeenCalledWith({
      custom_parameters: { thinking: { level: "low" } },
    });
  });

  it("stores nothing when the box is cleared and there is no thinking level", () => {
    const { onChange, editor } = renderBox({ topK: 40 });

    type(editor, "");

    expect(onChange).toHaveBeenCalledWith({ custom_parameters: null });
  });
});

describe("where the Gemini and Vertex AI panels offer the box", () => {
  it.each([
    ["Gemini", GeminiModelConfigs, UNLISTED_GEMINI],
    ["Vertex AI", VertexAIModelConfigs, UNLISTED_VERTEX],
  ])("%s: on the playground", (_, Panel, model) => {
    render(
      <TooltipProvider delayDuration={700}>
        <Panel configs={CONFIG} model={model} onChange={vi.fn()} />
      </TooltipProvider>,
    );

    expect(screen.getByTestId("extra-body-editor")).toBeInTheDocument();
  });

  it.each([
    [
      "an evaluator rule, whose judge reads only the thinking level",
      RULE_UNSUPPORTED_PARAMS,
    ],
    [
      "the optimizer, which calls Gemini through LiteLLM",
      OPTIMIZATION_UNSUPPORTED_PARAMS,
    ],
  ])("not on %s", (_, unsupportedParams) => {
    render(
      <TooltipProvider delayDuration={700}>
        <GeminiModelConfigs
          configs={CONFIG}
          model={UNLISTED_GEMINI}
          onChange={vi.fn()}
          unsupportedParams={unsupportedParams}
        />
      </TooltipProvider>,
    );

    expect(screen.queryByTestId("extra-body-editor")).not.toBeInTheDocument();
  });

  it("on a dataset run, which the server sends with the same custom parameters", () => {
    render(
      <TooltipProvider delayDuration={700}>
        <GeminiModelConfigs
          configs={CONFIG}
          model={UNLISTED_GEMINI}
          onChange={vi.fn()}
          unsupportedParams={BACKEND_RUN_UNSUPPORTED_PARAMS}
        />
      </TooltipProvider>,
    );

    expect(screen.getByTestId("extra-body-editor")).toBeInTheDocument();
  });
});
