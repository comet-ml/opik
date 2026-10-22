import { describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";

import GenericModelConfigs from "./GenericModelConfigs";
import { GenericPanelPresentation } from "./genericPanels";
import { RULE_UNSUPPORTED_PARAMS } from "@/v2/pages-shared/llm/PromptModelSettings/modelConfigParams";
import { ModelParams } from "@/types/modelParams";
import { TooltipProvider } from "@/ui/tooltip";

const PRESENTATION: GenericPanelPresentation = {
  binding: {
    temperature: "temperature",
    top_p: "topP",
    max_output_tokens: "maxCompletionTokens",
    effort: "thinkingEffort",
  },
  storedNumbersOnly: false,
  reasoningNoun: "thinking",
  effortLabel: "Thinking effort",
  effortDescription: "How hard the model thinks.",
  effortPlaceholder: "Select thinking effort",
  getEffortLabel: (level) => level.toUpperCase(),
  getStoredEffort: (configs) => [configs.thinkingEffort],
  throttlingDefault: 0,
  maxConcurrentRequestsDefault: 5,
};

const SAMPLING_MODEL: ModelParams = {
  temperature: { accepted: true, min: 0, max: 1, default: 0 },
  top_p: { accepted: true, min: 0, max: 1, default: 1 },
  max_output_tokens: { accepted: true, min: 1, max: 8000, default: 4000 },
  effort: { accepted: true, levels: ["low", "high"], default: "high" },
  sampling_exclusive: true,
};

const STORED_NUMBERS_PRESENTATION: GenericPanelPresentation = {
  ...PRESENTATION,
  binding: {
    temperature: "temperature",
    top_p: "topP",
    max_output_tokens: "maxCompletionTokens",
    frequency_penalty: "frequencyPenalty",
    presence_penalty: "presencePenalty",
    effort: "reasoningEffort",
  },
  storedNumbersOnly: true,
  getStoredEffort: (configs) => [configs.reasoningEffort],
};

const INDEPENDENT_SAMPLING_MODEL: ModelParams = {
  temperature: { accepted: true, min: 0, max: 2, default: 0 },
  top_p: { accepted: true, min: 0, max: 1, default: 1 },
  max_output_tokens: { accepted: true, min: 16, max: 8000, default: 4000 },
  frequency_penalty: { accepted: true, min: -2, max: 2, default: 0 },
  presence_penalty: { accepted: true, min: -2, max: 2, default: 0 },
  effort: {
    accepted: true,
    levels: ["low", "medium", "high"],
    default: "medium",
  },
};

const NO_SAMPLING_MODEL: ModelParams = {
  ...SAMPLING_MODEL,
  temperature: { accepted: false },
  top_p: { accepted: false },
  effort: { accepted: false },
};

const renderPanel = (
  params: ModelParams,
  configs: Record<string, unknown>,
  unsupportedParams?: ReadonlySet<never> | typeof RULE_UNSUPPORTED_PARAMS,
  onChange = vi.fn(),
  presentation = PRESENTATION,
) => {
  render(
    <TooltipProvider delayDuration={700}>
      <GenericModelConfigs
        params={params}
        presentation={presentation}
        configs={configs}
        onChange={onChange}
        unsupportedParams={unsupportedParams}
      />
    </TooltipProvider>,
  );
  return onChange;
};

describe("GenericModelConfigs", () => {
  it("draws the model's controls from its params", () => {
    renderPanel(SAMPLING_MODEL, {
      temperature: 0.7,
      maxCompletionTokens: 99999,
      thinkingEffort: "low",
    });

    expect(screen.getByText("Sampling")).toBeInTheDocument();
    expect(screen.getByTestId("temperature-input")).toHaveValue("0.7");
    expect(screen.queryByTestId("topP-input")).not.toBeInTheDocument();
    expect(screen.getByTestId("maxCompletionTokens-input")).toHaveValue("8000");
    expect(screen.getByTestId("throttling-input")).toBeInTheDocument();
    expect(
      screen.getByTestId("maxConcurrentRequests-input"),
    ).toBeInTheDocument();
    expect(screen.getByRole("combobox")).toHaveTextContent("LOW");
  });

  it("offers exactly the model's effort levels", () => {
    renderPanel(SAMPLING_MODEL, {});

    expect(screen.getByRole("combobox")).toHaveTextContent("HIGH");
    fireEvent.keyDown(screen.getByRole("combobox"), { key: "Enter" });
    expect(
      screen.getAllByRole("option").map((option) => option.textContent),
    ).toEqual(["LOW", "HIGH"]);
  });

  it("writes the picked level to the provider's config key", () => {
    const onChange = renderPanel(SAMPLING_MODEL, {});

    fireEvent.keyDown(screen.getByRole("combobox"), { key: "Enter" });
    fireEvent.keyDown(screen.getByRole("option", { name: "LOW" }), {
      key: "Enter",
    });

    expect(onChange).toHaveBeenCalledWith({ thinkingEffort: "low" });
  });

  it("switches the sampling half and clears the other", () => {
    const onChange = renderPanel(SAMPLING_MODEL, { temperature: 0.7 });

    fireEvent.click(screen.getByRole("radio", { name: "Top P" }));

    expect(onChange).toHaveBeenCalledWith({ topP: 1, temperature: undefined });
  });

  it("shows no sampling or effort control for a model that refuses them", () => {
    renderPanel(NO_SAMPLING_MODEL, { temperature: 0.7, thinkingEffort: "low" });

    expect(screen.queryByText("Sampling")).not.toBeInTheDocument();
    expect(screen.queryByTestId("temperature-input")).not.toBeInTheDocument();
    expect(screen.queryByRole("combobox")).not.toBeInTheDocument();
    expect(screen.getByTestId("maxCompletionTokens-input")).toBeInTheDocument();
  });

  it("keeps to what an evaluator rule can store", () => {
    renderPanel(SAMPLING_MODEL, { temperature: 0.7 }, RULE_UNSUPPORTED_PARAMS);

    expect(screen.queryByText("Sampling")).not.toBeInTheDocument();
    expect(screen.getByTestId("temperature-input")).toHaveValue("0.7");
    expect(
      screen.queryByTestId("maxCompletionTokens-input"),
    ).not.toBeInTheDocument();
    expect(screen.queryByTestId("throttling-input")).not.toBeInTheDocument();
    expect(
      screen.queryByTestId("maxConcurrentRequests-input"),
    ).not.toBeInTheDocument();
    expect(screen.getByRole("combobox")).toBeInTheDocument();
  });

  it("offers a slider only for a number the config holds, when the provider's panel works that way", () => {
    renderPanel(
      INDEPENDENT_SAMPLING_MODEL,
      { temperature: 0.5, frequencyPenalty: 0.2, reasoningEffort: "low" },
      undefined,
      vi.fn(),
      STORED_NUMBERS_PRESENTATION,
    );

    expect(screen.queryByText("Sampling")).not.toBeInTheDocument();
    expect(screen.getByTestId("temperature-input")).toHaveValue("0.5");
    expect(screen.getByTestId("frequencyPenalty-input")).toHaveValue("0.2");
    expect(screen.queryByTestId("topP-input")).not.toBeInTheDocument();
    expect(
      screen.queryByTestId("presencePenalty-input"),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByTestId("maxCompletionTokens-input"),
    ).not.toBeInTheDocument();
    expect(screen.getByRole("combobox")).toHaveTextContent("LOW");
  });

  it("offers no penalty the route refuses, even when the config holds one", () => {
    renderPanel(
      {
        ...INDEPENDENT_SAMPLING_MODEL,
        frequency_penalty: { accepted: false },
        presence_penalty: { accepted: false },
      },
      { frequencyPenalty: 0.2, presencePenalty: 0.1, maxCompletionTokens: 4 },
      undefined,
      vi.fn(),
      STORED_NUMBERS_PRESENTATION,
    );

    expect(
      screen.queryByTestId("frequencyPenalty-input"),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByTestId("presencePenalty-input"),
    ).not.toBeInTheDocument();
    expect(screen.getByTestId("maxCompletionTokens-input")).toHaveValue("16");
  });
});
