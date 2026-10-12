import { describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";

// The custom panel embeds the real CodeMirror editor for custom_parameters, which does not mount
// under this environment (a duplicate @codemirror/state breaks its instanceof checks).
vi.mock("@uiw/react-codemirror", () => ({
  default: () => <div data-testid="codemirror-stub" />,
}));

import PromptModelConfigs from "./PromptModelConfigs";
import {
  COMPOSED_PROVIDER_TYPE,
  LLMPromptConfigsType,
  PROVIDER_MODEL_TYPE,
  PROVIDER_TYPE,
} from "@/types/providers";
import { getDefaultConfigByProvider } from "@/lib/playground";
import { TooltipProvider } from "@/ui/tooltip";

const renderTrigger = (
  provider: COMPOSED_PROVIDER_TYPE,
  model: PROVIDER_MODEL_TYPE | "" = PROVIDER_MODEL_TYPE.OPIK_FREE_MODEL,
  configs: Partial<LLMPromptConfigsType> = {},
  onChange = vi.fn(),
) =>
  render(
    <TooltipProvider delayDuration={700}>
      <PromptModelConfigs
        provider={provider}
        model={model}
        configs={configs}
        onChange={onChange}
      />
    </TooltipProvider>,
  );

const openPanel = () => {
  // Radix opens dropdowns on pointerdown, not click.
  fireEvent.pointerDown(
    screen.getByRole("button"),
    new PointerEvent("pointerdown", { bubbles: true, button: 0 }),
  );
};

describe("PromptModelConfigs trigger", () => {
  it("is hidden for the Opik free model, which has no parameters to set", () => {
    renderTrigger(PROVIDER_TYPE.OPIK_FREE);

    expect(screen.queryByRole("button")).not.toBeInTheDocument();
  });

  it("is enabled for a provider with parameters", () => {
    renderTrigger(PROVIDER_TYPE.OPEN_AI);

    expect(screen.getByRole("button")).toBeEnabled();
  });

  it("stays disabled before a provider is picked", () => {
    renderTrigger("");

    expect(screen.getByRole("button")).toBeDisabled();
  });
});

describe("PromptModelConfigs for Ollama and Bedrock", () => {
  it.each([
    ["Ollama", `${PROVIDER_TYPE.OLLAMA}:local`, "custom-llm/local/llama3.2"],
    [
      "Bedrock",
      `${PROVIDER_TYPE.BEDROCK}:aws`,
      "custom-llm/aws/openai.gpt-oss-120b-1:0",
    ],
  ])("opens the custom panel for %s", (_, provider, model) => {
    renderTrigger(
      provider,
      model as PROVIDER_MODEL_TYPE,
      getDefaultConfigByProvider(provider, model as PROVIDER_MODEL_TYPE),
    );

    openPanel();

    expect(screen.getByTestId("temperature-input")).toHaveValue("0");
    expect(screen.getByTestId("maxCompletionTokens-input")).toHaveValue("4000");
    expect(screen.getByTestId("topP-input")).toHaveValue("1");
    expect(screen.getByText("Extra body parameters (Optional)")).toBeVisible();
  });
});

describe("PromptModelConfigs typed values", () => {
  it("keeps a typed value when Escape closes the panel", () => {
    const onChange = vi.fn();
    renderTrigger(
      PROVIDER_TYPE.OPEN_AI,
      PROVIDER_MODEL_TYPE.GPT_4O_MINI,
      getDefaultConfigByProvider(
        PROVIDER_TYPE.OPEN_AI,
        PROVIDER_MODEL_TYPE.GPT_4O_MINI,
      ),
      onChange,
    );

    openPanel();
    const input = screen.getByTestId("temperature-input");
    fireEvent.change(input, { target: { value: "0.7" } });
    fireEvent.keyDown(input, { key: "Escape" });

    expect(onChange).toHaveBeenCalledWith({ temperature: 0.7 });
  });
});
