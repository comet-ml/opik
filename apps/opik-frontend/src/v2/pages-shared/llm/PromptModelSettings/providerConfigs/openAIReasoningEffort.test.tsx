import { describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";

import OpenAIModelConfigs from "./OpenAIModelConfigs";
import {
  LLMOpenAIConfigsType,
  OpenAiPipelineMode,
  PROVIDER_MODEL_TYPE,
} from "@/types/providers";
import { TooltipProvider } from "@/ui/tooltip";

const CONFIG: LLMOpenAIConfigsType = {
  temperature: 0,
  maxCompletionTokens: 4000,
  topP: 1,
  frequencyPenalty: 0,
  presencePenalty: 0,
  reasoningEffort: "max",
};

const renderPanel = (
  model: PROVIDER_MODEL_TYPE,
  openAiPipelineMode?: OpenAiPipelineMode,
) =>
  render(
    <TooltipProvider delayDuration={700}>
      <OpenAIModelConfigs
        configs={CONFIG}
        model={model}
        onChange={vi.fn()}
        openAiPipelineMode={openAiPipelineMode}
      />
    </TooltipProvider>,
  );

const openEffortDropdown = () => {
  fireEvent.keyDown(screen.getByRole("combobox"), { key: "Enter" });
  return screen.getAllByRole("option").map((option) => option.textContent);
};

describe("the OpenAI reasoning effort dropdown", () => {
  it("offers Max for GPT-6 Sol on a Responses API key and shows the stored max", () => {
    renderPanel(PROVIDER_MODEL_TYPE.GPT_6_SOL, "responses_api");

    expect(screen.getByRole("combobox")).toHaveTextContent("Max");
    expect(openEffortDropdown()).toEqual([
      "None",
      "Low",
      "Medium",
      "High",
      "xHigh",
      "Max",
    ]);
  });

  it.each<OpenAiPipelineMode | undefined>([undefined, "chat_completions_api"])(
    "leaves Max out and shows a stored max as High when the mode is %s",
    (mode) => {
      renderPanel(PROVIDER_MODEL_TYPE.GPT_6_SOL, mode);

      expect(screen.getByRole("combobox")).toHaveTextContent("High");
      expect(openEffortDropdown()).toEqual([
        "None",
        "Low",
        "Medium",
        "High",
        "xHigh",
      ]);
    },
  );

  it("does not offer Max on a Responses API key for a model without it", () => {
    renderPanel(PROVIDER_MODEL_TYPE.GPT_5_5, "responses_api");

    expect(openEffortDropdown()).toEqual([
      "None",
      "Low",
      "Medium",
      "High",
      "xHigh",
    ]);
  });
});

describe("the OpenAI penalty sliders", () => {
  it("are not rendered on a Responses API key", () => {
    renderPanel(PROVIDER_MODEL_TYPE.GPT_4O, "responses_api");

    expect(screen.getByText("Temperature")).toBeInTheDocument();
    expect(screen.queryByText("Frequency penalty")).not.toBeInTheDocument();
    expect(screen.queryByText("Presence penalty")).not.toBeInTheDocument();
  });

  it.each<OpenAiPipelineMode | undefined>([undefined, "chat_completions_api"])(
    "are rendered when the mode is %s",
    (mode) => {
      renderPanel(PROVIDER_MODEL_TYPE.GPT_4O, mode);

      expect(screen.getByText("Frequency penalty")).toBeInTheDocument();
      expect(screen.getByText("Presence penalty")).toBeInTheDocument();
    },
  );
});
