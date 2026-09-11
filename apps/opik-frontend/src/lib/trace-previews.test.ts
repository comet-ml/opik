import { describe, it, expect } from "vitest";
import {
  getPrettifyConfig,
  getThreadPrettifyConfig,
  prettifyThreadField,
  prettifyTraceField,
} from "./traces";
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

describe("thread preview and export context", () => {
  it("extracts canonical messages without source metadata", () => {
    const thread = {
      first_message: { messages: [{ role: "human", content: "Question" }] },
      last_message: {
        messages: [
          { role: "assistant", content: "Answer" },
          { role: "assistant", tool_calls: [{ function: { name: "search" } }] },
        ],
      },
    };
    expect(prettifyThreadField(thread, "input").message).toBe("Question");
    expect(prettifyThreadField(thread, "output").message).toBe("Answer");
  });

  it("extracts role-less multimodal content", () => {
    const thread = {
      last_message: { messages: [{ contents: [{ text: "Nested answer" }] }] },
    };
    expect(prettifyThreadField(thread, "output").message).toBe("Nested answer");
  });

  it.each([
    {
      choices: [{ message: { role: "assistant", content: "Provider answer" } }],
    },
    { messages: [{ type: "ai", content: "Provider answer" }] },
    { answer: "Provider answer" },
  ])(
    "preserves existing provider and generic formatting: %j",
    (last_message) => {
      expect(prettifyThreadField({ last_message }, "output").message).toBe(
        "Provider answer",
      );
    },
  );

  it("does not infer output from a different trace's first message", () => {
    const thread = {
      first_message: { "llm.output_messages.0.message.content": "Old answer" },
    };
    expect(prettifyThreadField(thread, "output").message).toBeUndefined();
  });

  it("does not infer a format from arbitrary raw thread data", () => {
    const thread = { last_message: { request_id: "raw", status: "done" } };
    expect(getThreadPrettifyConfig(thread, "output").openInferenceHint).toBe(
      false,
    );
    expect(prettifyThreadField(thread, "output").prettified).toBe(false);
  });
});
