import { describe, expect, it } from "vitest";

import { LlmModelsByProvider } from "@/api/llm/useLlmModels";
import {
  formatRetirementDate,
  getModelRetirement,
} from "@/lib/modelRetirement";

const TODAY = new Date("2026-10-23T15:00:00Z");

const model = (id: string, retires_on?: string, qualified_name?: string) => ({
  id,
  qualified_name,
  structured_output: false,
  reasoning: false,
  retires_on,
});

const REGISTRY: LlmModelsByProvider = {
  openai: [
    model("o4-mini", "2026-10-23"),
    model("gpt-4", "2026-06-01"),
    model("gpt-5-nano"),
    model("gpt-5.4-nano", "2026-11-10"),
    model("gpt-5.2", "2026-11-22"),
    model("gpt-5.1", "2026-11-23"),
  ],
  gemini: [model("gemini-2.5-flash")],
  "vertex-ai": [
    model("gemini-2.5-flash", "2026-10-20", "vertex_ai/gemini-2.5-flash"),
  ],
};

describe("getModelRetirement", () => {
  it.each([
    ["o4-mini", "openai", { date: "2026-10-23", retired: true }],
    ["gpt-4", "openai", { date: "2026-06-01", retired: true }],
    ["gpt-5.4-nano", "openai", { date: "2026-11-10", retired: false }],
    ["gpt-5.2", "openai", { date: "2026-11-22", retired: false }],
    ["gpt-5.1", "openai", undefined],
    ["gpt-5-nano", "openai", undefined],
    ["not-listed", "openai", undefined],
  ])("%s on %s", (id, provider, expected) => {
    expect(getModelRetirement(REGISTRY, id, provider, TODAY)).toEqual(expected);
  });

  it("reads only the provider's own list, so a Vertex AI date never marks the Gemini API model", () => {
    expect(
      getModelRetirement(REGISTRY, "gemini-2.5-flash", "gemini", TODAY),
    ).toBeUndefined();
    expect(
      getModelRetirement(
        REGISTRY,
        "vertex_ai/gemini-2.5-flash",
        "vertex-ai",
        TODAY,
      ),
    ).toEqual({ date: "2026-10-20", retired: true });
  });

  it("needs a registry, a model and a provider", () => {
    expect(
      getModelRetirement(undefined, "o4-mini", "openai", TODAY),
    ).toBeUndefined();
    expect(getModelRetirement(REGISTRY, "", "openai", TODAY)).toBeUndefined();
    expect(
      getModelRetirement(REGISTRY, "o4-mini", undefined, TODAY),
    ).toBeUndefined();
  });

  it("ignores a date it can't read", () => {
    expect(
      getModelRetirement(
        { openai: [model("o3", "soon")] },
        "o3",
        "openai",
        TODAY,
      ),
    ).toBeUndefined();
  });
});

describe("formatRetirementDate", () => {
  it("prints the calendar date, whatever the time zone", () => {
    expect(formatRetirementDate("2026-10-23")).toBe("Oct 23, 2026");
  });
});
