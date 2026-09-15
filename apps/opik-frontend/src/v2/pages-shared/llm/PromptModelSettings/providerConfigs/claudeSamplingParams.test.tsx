import { describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";

// The custom panel embeds the real CodeMirror editor for custom_parameters, which does not mount
// under this environment (a duplicate @codemirror/state breaks its instanceof checks). Nothing here
// touches that editor — same stub as CodeMetricConfigs.test.tsx.
vi.mock("@uiw/react-codemirror", () => ({
  default: () => <div data-testid="codemirror-stub" />,
}));

import CustomModelConfigs from "./CustomModelConfig";
import OpenRouterModelConfigs from "./OpenRouterModelConfigs";
import {
  LLMCustomConfigsType,
  LLMOpenRouterConfigsType,
  PROVIDER_MODEL_TYPE,
} from "@/types/providers";
import { TooltipProvider } from "@/ui/tooltip";

const renderPanel = (ui: React.ReactElement) =>
  render(<TooltipProvider delayDuration={700}>{ui}</TooltipProvider>);

const CUSTOM_CONFIG = {
  temperature: 0.7,
  maxCompletionTokens: 4000,
  topP: 0.9,
  frequencyPenalty: 0,
  presencePenalty: 0,
} as LLMCustomConfigsType;

const OPEN_ROUTER_CONFIG = {
  temperature: 0.7,
  maxTokens: 0,
  topP: 0.9,
  topK: 0,
  frequencyPenalty: 0,
  presencePenalty: 0,
  repetitionPenalty: 1,
  minP: 0,
  topA: 0,
} as LLMOpenRouterConfigsType;

// Claude rejects temperature and top_p together whoever serves it, so the panel has to present the
// same either/or choice it presents under the Anthropic provider — otherwise a Top P set here is
// silently dropped from the request.
describe("a Claude model behind another provider", () => {
  it("offers the sampling choice on the custom panel", () => {
    renderPanel(
      <CustomModelConfigs
        configs={CUSTOM_CONFIG}
        model={"claude-opus-4-6" as PROVIDER_MODEL_TYPE}
        onChange={vi.fn()}
      />,
    );

    expect(screen.getByText("Sampling")).toBeInTheDocument();
    expect(screen.getByRole("radio", { name: "Temperature" })).toBeChecked();
    expect(screen.getByTestId("temperature-input")).toHaveValue("0.7");
    expect(screen.queryByTestId("topP-input")).not.toBeInTheDocument();
  });

  it("offers it on the openrouter panel too", () => {
    renderPanel(
      <OpenRouterModelConfigs
        configs={OPEN_ROUTER_CONFIG}
        model={PROVIDER_MODEL_TYPE.ANTHROPIC_CLAUDE_SONNET_5}
        onChange={vi.fn()}
      />,
    );

    expect(screen.getByText("Sampling")).toBeInTheDocument();
    expect(screen.queryByTestId("topP-input")).not.toBeInTheDocument();
  });

  it("clears temperature when the choice moves to Top P", () => {
    const onChange = vi.fn();
    renderPanel(
      <CustomModelConfigs
        configs={CUSTOM_CONFIG}
        model={
          "us.anthropic.claude-sonnet-4-5-20250929-v1:0" as PROVIDER_MODEL_TYPE
        }
        onChange={onChange}
      />,
    );

    fireEvent.click(screen.getByRole("radio", { name: "Top P" }));

    expect(onChange).toHaveBeenCalledWith({ topP: 1, temperature: undefined });
  });
});

describe("a Claude config that carries neither half", () => {
  // Only the Anthropic provider restores a default when a config carries neither half. A Claude
  // model reached another way resolves to neither, and the request then carries neither — so
  // rendering a slider on its default would claim a value that never leaves.
  it("offers the choice unselected, with no slider claiming a value", () => {
    renderPanel(
      <CustomModelConfigs
        configs={{ maxCompletionTokens: 4000 } as LLMCustomConfigsType}
        model={"custom-llm/gw/claude-opus-4-6" as PROVIDER_MODEL_TYPE}
        onChange={vi.fn()}
      />,
    );

    expect(
      screen.getByRole("radio", { name: "Temperature" }),
    ).not.toBeChecked();
    expect(screen.getByRole("radio", { name: "Top P" })).not.toBeChecked();
    expect(screen.queryByTestId("temperature-input")).not.toBeInTheDocument();
    expect(screen.queryByTestId("topP-input")).not.toBeInTheDocument();
  });

  it("lets the user pick a half from that state", () => {
    const onChange = vi.fn();
    renderPanel(
      <CustomModelConfigs
        configs={{ maxCompletionTokens: 4000 } as LLMCustomConfigsType}
        model={"custom-llm/gw/claude-opus-4-6" as PROVIDER_MODEL_TYPE}
        onChange={onChange}
      />,
    );

    fireEvent.click(screen.getByRole("radio", { name: "Temperature" }));

    expect(onChange).toHaveBeenCalledWith({ temperature: 0, topP: undefined });
  });

  it("still shows the half the config does carry", () => {
    renderPanel(
      <CustomModelConfigs
        configs={
          {
            maxCompletionTokens: 4000,
            temperature: 0.4,
          } as LLMCustomConfigsType
        }
        model={"custom-llm/gw/claude-opus-4-6" as PROVIDER_MODEL_TYPE}
        onChange={vi.fn()}
      />,
    );

    expect(screen.getByTestId("temperature-input")).toHaveValue("0.4");
  });
});

describe("a model without the constraint on the same panels", () => {
  it("keeps both sliders independent on the custom panel", () => {
    renderPanel(
      <CustomModelConfigs
        configs={CUSTOM_CONFIG}
        model={"mistral-large-2411" as PROVIDER_MODEL_TYPE}
        onChange={vi.fn()}
      />,
    );

    expect(screen.queryByText("Sampling")).not.toBeInTheDocument();
    expect(screen.getByTestId("temperature-input")).toHaveValue("0.7");
    expect(screen.getByTestId("topP-input")).toHaveValue("0.9");
  });

  it("keeps both sliders independent on the openrouter panel", () => {
    renderPanel(
      <OpenRouterModelConfigs
        configs={OPEN_ROUTER_CONFIG}
        model={PROVIDER_MODEL_TYPE.OPENAI_GPT_4O}
        onChange={vi.fn()}
      />,
    );

    expect(screen.queryByText("Sampling")).not.toBeInTheDocument();
    expect(screen.getByTestId("topP-input")).toHaveValue("0.9");
  });
});
