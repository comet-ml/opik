/* eslint-disable @typescript-eslint/no-explicit-any */
import { describe, expect, it } from "vitest";
import { trackGemini } from "../src";

const content = { parts: [{ text: "prefix" }], role: "model" };
const usageMetadata = {
  promptTokenCount: 7,
  candidatesTokenCount: 3,
  totalTokenCount: 10,
};
const reason = "MALFORMED_FUNCTION_CALL";

const runStream = async (chunks: Array<Record<string, unknown>>) => {
  const spans: Array<Record<string, any>> = [];
  const sdk = {
    models: {
      // eslint-disable-next-line @typescript-eslint/no-unused-vars
      async generateContentStream(_args: unknown) {
        return (async function* () {
          yield* chunks;
        })();
      },
    },
  };
  const client = {
    trace() {
      return {
        span(data: Record<string, any>) {
          spans.push(data);
          return { span() {} };
        },
        update() {},
      };
    },
    flush: async () => {},
  };
  const tracked = trackGemini(sdk, { client: client as any });
  const stream = await tracked.models.generateContentStream({
    model: "gemini-test",
    contents: "test",
  });
  const received: unknown[] = [];
  for await (const chunk of stream as AsyncIterable<unknown>) {
    received.push(chunk);
  }
  expect(received).toHaveLength(chunks.length);
  return spans[0];
};

describe("trackGemini streamed span metadata", () => {
  it("keeps finish_reason and usage when they arrive in different chunks", async () => {
    const span = await runStream([
      { candidates: [{ content }] },
      { candidates: [{ finishReason: reason }] },
      { usageMetadata },
    ]);

    expect(span.metadata.finish_reason).toBe(reason);
    expect(span.usage.total_tokens).toBe(10);
  });

  it("keeps finish_reason and usage when they share the final chunk", async () => {
    const span = await runStream([
      { candidates: [{ content }] },
      { candidates: [{ finishReason: reason }], usageMetadata },
    ]);

    expect(span.metadata.finish_reason).toBe(reason);
    expect(span.usage.total_tokens).toBe(10);
  });
});
