import { describe, expect, it } from "vitest";

import { detectLLMMessages } from "./detectLLMMessages";

const AMBIGUOUS_INPUT = {
  messages: [
    {
      role: "user",
      type: "human",
      content: "Hello",
    },
  ],
};

describe("detectLLMMessages", () => {
  it("uses registry order when no format hint is provided", () => {
    expect(detectLLMMessages(AMBIGUOUS_INPUT, { fieldType: "input" })).toEqual({
      supported: true,
      format: "openai",
      confidence: "medium",
    });
  });

  it("tries the hinted format before registry order", () => {
    expect(
      detectLLMMessages(AMBIGUOUS_INPUT, { fieldType: "input" }, "langchain"),
    ).toEqual({
      supported: true,
      format: "langchain",
      confidence: "high",
    });
  });

  it("falls back to registry order when the hint is unsupported", () => {
    expect(
      detectLLMMessages(AMBIGUOUS_INPUT, { fieldType: "input" }, "anthropic"),
    ).toEqual({
      supported: true,
      format: "openai",
      confidence: "low",
    });
  });

  it("safely falls back for a prototype property hint", () => {
    expect(
      detectLLMMessages(AMBIGUOUS_INPUT, { fieldType: "input" }, "__proto__"),
    ).toEqual({
      supported: true,
      format: "openai",
      confidence: "low",
    });
  });
});
