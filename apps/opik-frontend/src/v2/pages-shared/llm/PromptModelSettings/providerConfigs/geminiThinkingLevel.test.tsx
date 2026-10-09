import { describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";

import GeminiModelConfigs from "./GeminiModelConfigs";
import VertexAIModelConfigs from "./VertexAIModelConfigs";
import { GeminiThinkingLevel, PROVIDER_MODEL_TYPE } from "@/types/providers";
import { TooltipProvider } from "@/ui/tooltip";

const configsWith = (thinkingLevel: GeminiThinkingLevel) => ({
  temperature: 0,
  maxCompletionTokens: 4000,
  topP: 1,
  thinkingLevel,
});

const PANELS = {
  Gemini: (model: PROVIDER_MODEL_TYPE, thinkingLevel: GeminiThinkingLevel) =>
    render(
      <TooltipProvider>
        <GeminiModelConfigs
          configs={configsWith(thinkingLevel)}
          model={model}
          onChange={vi.fn()}
        />
      </TooltipProvider>,
    ),
  "Vertex AI": (
    model: PROVIDER_MODEL_TYPE,
    thinkingLevel: GeminiThinkingLevel,
  ) =>
    render(
      <TooltipProvider>
        <VertexAIModelConfigs
          configs={configsWith(thinkingLevel)}
          model={model}
          onChange={vi.fn()}
        />
      </TooltipProvider>,
    ),
};

describe("the Gemini and Vertex AI thinking level dropdown", () => {
  it.each<
    [keyof typeof PANELS, PROVIDER_MODEL_TYPE, GeminiThinkingLevel, string]
  >([
    ["Gemini", PROVIDER_MODEL_TYPE.GEMINI_2_5_FLASH, "minimal", "Auto"],
    ["Gemini", PROVIDER_MODEL_TYPE.GEMINI_2_5_FLASH, "low", "Low"],
    [
      "Vertex AI",
      PROVIDER_MODEL_TYPE.VERTEX_AI_GEMINI_2_5_FLASH,
      "minimal",
      "Auto",
    ],
    [
      "Vertex AI",
      PROVIDER_MODEL_TYPE.VERTEX_AI_GEMINI_2_5_FLASH,
      "high",
      "High",
    ],
  ])(
    "%s shows a stored level on %s: %s as %s",
    (panel, model, stored, shown) => {
      PANELS[panel](model, stored);

      expect(screen.getByRole("combobox")).toHaveTextContent(shown);
    },
  );
});
