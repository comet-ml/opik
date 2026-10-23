import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";

import { TooltipProvider } from "@/ui/tooltip";
import ModelRetirementTag from "@/v2/pages-shared/llm/ModelRetirementTag/ModelRetirementTag";

const mocks = vi.hoisted(() => ({
  registry: {
    openai: [
      {
        id: "o4-mini",
        structured_output: false,
        reasoning: true,
        retires_on: "2026-10-23",
      },
      {
        id: "gpt-5.4-nano",
        structured_output: true,
        reasoning: true,
        retires_on: "2026-11-10",
      },
      { id: "gpt-5-nano", structured_output: true, reasoning: true },
    ],
  },
}));

vi.mock("@/api/llm/useLlmModels", () => ({
  default: () => ({ data: mocks.registry }),
}));

const renderTag = (model: string, provider = "openai") =>
  render(
    <TooltipProvider>
      <ModelRetirementTag model={model} provider={provider} />
    </TooltipProvider>,
  );

describe("ModelRetirementTag", () => {
  beforeEach(() => {
    vi.useFakeTimers({ toFake: ["Date"] });
    vi.setSystemTime(new Date("2026-10-23T12:00:00Z"));
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it("warns that a retired model is retired", () => {
    renderTag("o4-mini");

    expect(screen.getByTestId("model-retirement-tag")).toHaveTextContent(
      "Retired",
    );
  });

  it("warns about a model that retires within 30 days", () => {
    renderTag("gpt-5.4-nano");

    expect(screen.getByTestId("model-retirement-tag")).toHaveTextContent(
      "Retires Nov 10, 2026",
    );
  });

  it.each([
    ["gpt-5-nano", "openai"],
    ["o4-mini", "anthropic"],
  ])("shows nothing for %s on %s", (model, provider) => {
    renderTag(model, provider);

    expect(screen.queryByTestId("model-retirement-tag")).toBeNull();
  });
});
