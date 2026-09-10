import { describe, it, expect } from "vitest";
import { getPrettifyConfig, prettifyTraceField } from "./traces";
describe("trace preview context", () => {
  it("recovers output with legacy attributes only in input", () => {
    const trace = {
      input: { "llm.output_messages.0.message.content": "Recovered" },
    };
    expect(prettifyTraceField(trace, "output").message).toBe("Recovered");
    expect(
      getPrettifyConfig(trace, "input").openInferenceInput,
    ).toBeUndefined();
  });
  it("uses a metadata-only marker and preserves role-less messages", () => {
    const trace = {
      metadata: { "openinference.span.kind": "LLM" },
      output: { messages: [{ content: "Answer" }] },
    };
    expect(prettifyTraceField(trace, "output").message).toBe("Answer");
  });
  it("reads format evidence from output too", () => {
    const trace = {
      output: { "llm.output_messages.0.message.content": "Answer" },
    };
    expect(getPrettifyConfig(trace, "input").openInferenceHint).toBe(true);
  });
  it("leaves an ordinary empty trace empty", () => {
    expect(prettifyTraceField({}, "output").message).toBeUndefined();
  });
});
