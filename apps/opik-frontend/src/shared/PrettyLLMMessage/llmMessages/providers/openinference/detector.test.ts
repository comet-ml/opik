import { describe, expect, it } from "vitest";
import { detectLLMMessages } from "../../detectLLMMessages";
import { detectOpenInferenceFormat } from "./detector";

describe("detectOpenInferenceFormat", () => {
  it("detects historical flattened attributes without a hint", () => {
    expect(
      detectOpenInferenceFormat(
        {
          "openinference.span.kind": "LLM",
          "llm.input_messages.0.message.role": "user",
        },
        { fieldType: "input" },
      ),
    ).toBe(true);
  });

  it("detects a historical legacy function call without a marker", () => {
    expect(
      detectOpenInferenceFormat(
        { "llm.function_call": '{"name":"weather"}' },
        { fieldType: "output" },
      ),
    ).toBe(true);
  });

  it("uses a marker-derived hint for the canonical shape", () => {
    expect(
      detectLLMMessages(
        { messages: [{ role: "human", content: "hello" }] },
        { fieldType: "input" },
        "openinference",
      ),
    ).toMatchObject({
      supported: true,
      format: "openinference",
      confidence: "high",
    });
  });

  it("does not accept an arbitrary raw value just because it is hinted", () => {
    expect(
      detectOpenInferenceFormat(
        { value: "not enough to identify OpenInference" },
        { fieldType: "input", formatHint: "openinference" },
      ),
    ).toBe(false);
  });

  it("rejects canonical-looking data without a marker-derived hint", () => {
    expect(
      detectOpenInferenceFormat(
        { messages: [{ role: "user", content: "hello" }] },
        { fieldType: "input" },
      ),
    ).toBe(false);
  });
});
