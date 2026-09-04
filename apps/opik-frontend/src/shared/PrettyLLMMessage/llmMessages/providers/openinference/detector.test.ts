import { describe, expect, it } from "vitest";
import { canShowLLMMessages, detectLLMMessages } from "../../detectLLMMessages";
import { detectOpenInferenceFormat } from "./detector";

describe("detectOpenInferenceFormat", () => {
  it("detects historical flattened attributes without a hint", () => {
    expect(
      detectOpenInferenceFormat(
        {
          "openinference.span.kind": "LLM",
          "llm.input_messages.0.message.role": "user",
          "llm.input_messages.0.message.content": "Hello",
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

  it("accepts raw-only data when the hint comes from an exact marker", () => {
    expect(
      detectOpenInferenceFormat("raw OpenInference input", {
        fieldType: "input",
        formatHint: "openinference",
        formatHintIsAuthoritative: true,
      }),
    ).toBe(true);
    expect(
      detectOpenInferenceFormat(
        { value: "raw OpenInference output" },
        {
          fieldType: "output",
          formatHint: "openinference",
          formatHintIsAuthoritative: true,
        },
      ),
    ).toBe(true);
  });

  it("prefers a provider-specific shape over an authoritative raw fallback", () => {
    const output = detectLLMMessages(
      {
        id: "chatcmpl-1",
        model: "provider-model",
        choices: [
          { message: { role: "assistant", content: "Provider answer" } },
        ],
        usage: { total_tokens: 7 },
      },
      { fieldType: "output", formatHintIsAuthoritative: true },
      "openinference",
    );

    expect(output).toMatchObject({
      supported: true,
      format: "openai",
      confidence: "medium",
    });
    expect(
      canShowLLMMessages({ supported: false, empty: true }, output, true),
    ).toBe(true);
  });

  it("does not expose Messages for an authoritative marker without renderable fields", () => {
    expect(
      canShowLLMMessages(
        { supported: false, empty: true },
        { supported: false, empty: true },
        true,
      ),
    ).toBe(false);
  });

  it("does not treat canonical configuration fields as raw messages", () => {
    expect(
      detectOpenInferenceFormat(
        {
          invocation_parameters: { temperature: 0.2 },
          prompt_template: {
            template: "Answer {{question}}",
            variables: { question: "Why?" },
          },
        },
        {
          fieldType: "input",
          formatHint: "openinference",
          formatHintIsAuthoritative: true,
        },
      ),
    ).toBe(false);
  });

  it.each([
    { "openinference.span.kind": "LLM" },
    { "llm.finish_reason": "stop" },
    { "llm.output_messages.bad.message.role": "assistant" },
    {
      "llm.output_messages.0.message.tool_calls.0.tool_call.id": "only-id",
    },
  ])("does not expose Messages for non-renderable legacy data", (value) => {
    expect(detectOpenInferenceFormat(value, { fieldType: "output" })).toBe(
      false,
    );
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
