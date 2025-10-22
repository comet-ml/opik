import { trace } from "@opentelemetry/api";
import { getNodeAutoInstrumentations } from "@opentelemetry/auto-instrumentations-node";
import { NodeSDK } from "@opentelemetry/sdk-node";
import { generateText, tool } from "ai";
import { MockLanguageModelV2 } from "ai/test";
import type { LanguageModelV2ToolCall } from "@ai-sdk/provider";
import { Opik } from "opik";
import { OpikExporter } from "../src/exporter";
import { MockInstance } from "vitest";
import { z } from "zod";
import { mockAPIFunction } from "./mockUtils";

describe("Opik - Vercel AI SDK integration", () => {
  let client: Opik;
  let sdk: NodeSDK;
  let createSpansSpy: MockInstance<typeof client.api.spans.createSpans>;
  let updateSpansSpy: MockInstance<typeof client.api.spans.updateSpan>;
  let createTracesSpy: MockInstance<typeof client.api.traces.createTraces>;
  let updateTracesSpy: MockInstance<typeof client.api.traces.updateTrace>;

  beforeEach(() => {
    // Important! It resets the SDK to its default state
    trace.disable();

    client = new Opik({
      projectName: "opik-sdk-typescript",
    });
    sdk = new NodeSDK({
      traceExporter: new OpikExporter({ client }),
      instrumentations: [getNodeAutoInstrumentations()],
    });

    createSpansSpy = vi
      .spyOn(client.api.spans, "createSpans")
      .mockImplementation(mockAPIFunction as never);

    updateSpansSpy = vi
      .spyOn(client.api.spans, "updateSpan")
      .mockImplementation(mockAPIFunction as never);

    createTracesSpy = vi
      .spyOn(client.api.traces, "createTraces")
      .mockImplementation(mockAPIFunction as never);

    updateTracesSpy = vi
      .spyOn(client.api.traces, "updateTrace")
      .mockImplementation(mockAPIFunction as never);

    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.useRealTimers();

    createSpansSpy.mockRestore();
    createTracesSpy.mockRestore();
    updateSpansSpy.mockRestore();
    updateTracesSpy.mockRestore();
  });

  it("generateText", async () => {
    const input = "Hello, test!";
    const output = "Hello, world!";
    const traceName = "trace-name-example";

    sdk.start();

    const { text } = await generateText({
      model: new MockLanguageModelV2({
        doGenerate: {
          finishReason: "stop",
          usage: { inputTokens: 10, outputTokens: 20, totalTokens: 30 },
          content: [{ type: "text", text: output }],
          warnings: [],
        },
      }),
      prompt: input,
      experimental_telemetry: OpikExporter.getSettings({
        name: traceName,
      }),
    });

    await sdk.shutdown();

    expect(createTracesSpy).toHaveBeenCalledTimes(1);
    expect(createSpansSpy).toHaveBeenCalledTimes(1);
    expect(updateSpansSpy).toHaveBeenCalledTimes(0);
    expect(updateTracesSpy).toHaveBeenCalledTimes(0);
    expect(createTracesSpy.mock.calls[0][0]).toMatchObject({
      traces: [
        {
          input: {
            prompt: input,
          },
          metadata: {},
          name: traceName,
          output: { text },
          projectName: "opik-sdk-typescript",
          usage: {
            completion_tokens: 20,
            prompt_tokens: 10,
            total_tokens: 30,
          },
        },
      ],
    });
  });

  it("generateText with tools", async () => {
    const traceName = "trace-name-example";

    sdk.start();

    const calculator = tool({
      description: "calculate the sum of two numbers",
      inputSchema: z.object({ a: z.number(), b: z.number() }),
      execute: async ({ a, b }: { a: number; b: number }) => a + b,
    });

    const toolCallContent: LanguageModelV2ToolCall = {
      type: "tool-call",
      toolCallId: "1",
      toolName: "calculator",
      input: JSON.stringify({ a: 2, b: 4 }),
    };

    const response = await generateText({
      model: new MockLanguageModelV2({
        doGenerate: {
          finishReason: "tool-calls",
          usage: { inputTokens: 10, outputTokens: 20, totalTokens: 30 },
          content: [toolCallContent],
          warnings: [],
        },
      }),
      messages: [
        {
          role: "user",
          content: "How much is 2 + 4?",
        },
      ],
      tools: { calculator },
      experimental_telemetry: OpikExporter.getSettings({
        name: traceName,
      }),
    });

    let result = "";
    if (response.toolCalls && response.toolCalls.length > 0) {
      const [toolCall] = response.toolCalls;
      if (toolCall.type === "tool-call" && calculator.execute) {
        const toolResult = await calculator.execute(
          toolCall.input as { a: number; b: number },
          {
            toolCallId: toolCall.toolCallId,
            messages: [
              {
                role: "user",
                content: "How much is 2 + 4?",
              },
            ],
          }
        );
        result = String(toolResult);
      }
    }

    await sdk.shutdown();

    expect(result).toBe("6");
    expect(createTracesSpy).toHaveBeenCalledTimes(1);
    expect(createSpansSpy).toHaveBeenCalledTimes(1);
    expect(updateSpansSpy).toHaveBeenCalledTimes(0);
    expect(updateTracesSpy).toHaveBeenCalledTimes(0);
    expect(createTracesSpy.mock.calls[0][0]).toMatchObject({
      traces: [
        {
          input: {
            messages: [
              {
                role: "user",
                content: "How much is 2 + 4?",
              },
            ],
          },
          metadata: {},
          name: traceName,
          // @todo: if possible implement testing telemetry for tool calls
          output: {},
          projectName: "opik-sdk-typescript",
          usage: {
            completion_tokens: 20,
            prompt_tokens: 10,
            total_tokens: 30,
          },
        },
      ],
    });
  });
});
