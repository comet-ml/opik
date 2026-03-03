import { trace } from "@opentelemetry/api";
import { getNodeAutoInstrumentations } from "@opentelemetry/auto-instrumentations-node";
import { NodeSDK } from "@opentelemetry/sdk-node";
import { generateText, generateObject, tool } from "ai";
import { MockLanguageModelV3 } from "ai/test";
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
      model: new MockLanguageModelV3({
        doGenerate: {
          finishReason: { unified: "stop", raw: "stop" },
          usage: {
            inputTokens: { total: 10, noCache: undefined, cacheRead: undefined, cacheWrite: undefined },
            outputTokens: { total: 20, text: undefined, reasoning: undefined },
          },
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
      model: new MockLanguageModelV3({
        doGenerate: {
          finishReason: { unified: "tool-calls", raw: "tool_calls" },
          usage: {
            inputTokens: { total: 10, noCache: undefined, cacheRead: undefined, cacheWrite: undefined },
            outputTokens: { total: 20, text: undefined, reasoning: undefined },
          },
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

  it("generateText with threadId from constructor", async () => {
    const input = "Hello, test!";
    const output = "Hello, world!";
    const traceName = "trace-with-thread-id";
    const threadId = "thread-123";

    // Important! Reset SDK
    trace.disable();

    // Create new client and SDK with threadId
    const clientWithThreadId = new Opik({
      projectName: "opik-sdk-typescript",
    });
    const sdkWithThreadId = new NodeSDK({
      traceExporter: new OpikExporter({ client: clientWithThreadId, threadId }),
      instrumentations: [getNodeAutoInstrumentations()],
    });

    const createTracesSpyWithThreadId = vi
      .spyOn(clientWithThreadId.api.traces, "createTraces")
      .mockImplementation(mockAPIFunction as never);

    const createSpansSpyWithThreadId = vi
      .spyOn(clientWithThreadId.api.spans, "createSpans")
      .mockImplementation(mockAPIFunction as never);

    sdkWithThreadId.start();

    await generateText({
      model: new MockLanguageModelV3({
        doGenerate: {
          finishReason: { unified: "stop", raw: "stop" },
          usage: {
            inputTokens: { total: 10, noCache: undefined, cacheRead: undefined, cacheWrite: undefined },
            outputTokens: { total: 20, text: undefined, reasoning: undefined },
          },
          content: [{ type: "text", text: output }],
          warnings: [],
        },
      }),
      prompt: input,
      experimental_telemetry: OpikExporter.getSettings({
        name: traceName,
      }),
    });

    await sdkWithThreadId.shutdown();

    expect(createTracesSpyWithThreadId).toHaveBeenCalledTimes(1);
    expect(createSpansSpyWithThreadId).toHaveBeenCalledTimes(1);
    expect(createTracesSpyWithThreadId.mock.calls[0][0]).toMatchObject({
      traces: [
        {
          input: {
            prompt: input,
          },
          metadata: {},
          name: traceName,
          output: { text: output },
          projectName: "opik-sdk-typescript",
          threadId: threadId,
          usage: {
            completion_tokens: 20,
            prompt_tokens: 10,
            total_tokens: 30,
          },
        },
      ],
    });

    createTracesSpyWithThreadId.mockRestore();
    createSpansSpyWithThreadId.mockRestore();
  });

  it("generateText with threadId from telemetry metadata", async () => {
    const input = "Hello, test!";
    const output = "Hello, world!";
    const traceName = "trace-with-thread-id-metadata";
    const threadId = "thread-456";

    sdk.start();

    await generateText({
      model: new MockLanguageModelV3({
        doGenerate: {
          finishReason: { unified: "stop", raw: "stop" },
          usage: {
            inputTokens: { total: 10, noCache: undefined, cacheRead: undefined, cacheWrite: undefined },
            outputTokens: { total: 20, text: undefined, reasoning: undefined },
          },
          content: [{ type: "text", text: output }],
          warnings: [],
        },
      }),
      prompt: input,
      experimental_telemetry: OpikExporter.getSettings({
        name: traceName,
        metadata: {
          threadId: threadId,
        },
      }),
    });

    await sdk.shutdown();

    expect(createTracesSpy).toHaveBeenCalled();
    const lastCall = createTracesSpy.mock.calls[createTracesSpy.mock.calls.length - 1];
    expect(lastCall[0]).toMatchObject({
      traces: [
        {
          input: {
            prompt: input,
          },
          metadata: {},
          name: traceName,
          output: { text: output },
          projectName: "opik-sdk-typescript",
          threadId: threadId,
          usage: {
            completion_tokens: 20,
            prompt_tokens: 10,
            total_tokens: 30,
          },
        },
      ],
    });
  });

  it("generateText with threadId from telemetry metadata overrides constructor threadId", async () => {
    const input = "Hello, test!";
    const output = "Hello, world!";
    const traceName = "trace-with-thread-id-override";
    const constructorThreadId = "thread-constructor";
    const metadataThreadId = "thread-metadata";

    // Important! Reset SDK
    trace.disable();

    // Create new client and SDK with threadId in constructor
    const clientWithThreadId = new Opik({
      projectName: "opik-sdk-typescript",
    });
    const sdkWithThreadId = new NodeSDK({
      traceExporter: new OpikExporter({
        client: clientWithThreadId,
        threadId: constructorThreadId,
      }),
      instrumentations: [getNodeAutoInstrumentations()],
    });

    const createTracesSpyWithThreadId = vi
      .spyOn(clientWithThreadId.api.traces, "createTraces")
      .mockImplementation(mockAPIFunction as never);

    const createSpansSpyWithThreadId = vi
      .spyOn(clientWithThreadId.api.spans, "createSpans")
      .mockImplementation(mockAPIFunction as never);

    sdkWithThreadId.start();

    await generateText({
      model: new MockLanguageModelV3({
        doGenerate: {
          finishReason: { unified: "stop", raw: "stop" },
          usage: {
            inputTokens: { total: 10, noCache: undefined, cacheRead: undefined, cacheWrite: undefined },
            outputTokens: { total: 20, text: undefined, reasoning: undefined },
          },
          content: [{ type: "text", text: output }],
          warnings: [],
        },
      }),
      prompt: input,
      experimental_telemetry: OpikExporter.getSettings({
        name: traceName,
        metadata: {
          threadId: metadataThreadId,
        },
      }),
    });

    await sdkWithThreadId.shutdown();

    expect(createTracesSpyWithThreadId).toHaveBeenCalledTimes(1);
    expect(createSpansSpyWithThreadId).toHaveBeenCalledTimes(1);
    expect(createTracesSpyWithThreadId.mock.calls[0][0]).toMatchObject({
      traces: [
        {
          input: {
            prompt: input,
          },
          metadata: {},
          name: traceName,
          output: { text: output },
          projectName: "opik-sdk-typescript",
          threadId: metadataThreadId, // Should use metadata threadId, not constructor
          usage: {
            completion_tokens: 20,
            prompt_tokens: 10,
            total_tokens: 30,
          },
        },
      ],
    });

    createTracesSpyWithThreadId.mockRestore();
    createSpansSpyWithThreadId.mockRestore();
  });

  it("generateObject", async () => {
    const input = "Describe a weather forecast";
    const objectOutput = { weather: "sunny", temperature: 72 };
    const traceName = "trace-generate-object";

    sdk.start();

    const { object } = await generateObject({
      model: new MockLanguageModelV3({
        doGenerate: {
          finishReason: { unified: "stop", raw: "stop" },
          usage: {
            inputTokens: { total: 15, noCache: undefined, cacheRead: undefined, cacheWrite: undefined },
            outputTokens: { total: 25, text: undefined, reasoning: undefined },
          },
          content: [{ type: "text", text: JSON.stringify(objectOutput) }],
          warnings: [],
        },
      }),
      schema: z.object({
        weather: z.string(),
        temperature: z.number(),
      }),
      prompt: input,
      experimental_telemetry: OpikExporter.getSettings({
        name: traceName,
      }),
    });

    await sdk.shutdown();

    expect(object).toEqual(objectOutput);
    expect(createTracesSpy).toHaveBeenCalledTimes(1);
    expect(createSpansSpy).toHaveBeenCalledTimes(1);
    expect(createTracesSpy.mock.calls[0][0]).toMatchObject({
      traces: [
        {
          input: {
            prompt: input,
          },
          metadata: {},
          name: traceName,
          output: { object: objectOutput },
          projectName: "opik-sdk-typescript",
          usage: {
            completion_tokens: 25,
            prompt_tokens: 15,
            total_tokens: 40,
          },
        },
      ],
    });
  });
});
