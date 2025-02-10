import { getNodeAutoInstrumentations } from "@opentelemetry/auto-instrumentations-node";
import { NodeSDK } from "@opentelemetry/sdk-node";
import { generateText } from "ai";
import { MockLanguageModelV1 } from "ai/test";
import { Opik } from "opik";
import { OpikExporter } from "opik/vercel";
import { MockInstance } from "vitest";

async function mockAPIPromise<T>() {
  return {} as T;
}

describe.only("Opik - Vercel AI SDK integration", () => {
  let client: Opik;
  let sdk: NodeSDK;
  let createSpansSpy: MockInstance<typeof client.api.spans.createSpans>;
  let updateSpansSpy: MockInstance<typeof client.api.spans.updateSpan>;
  let createTracesSpy: MockInstance<typeof client.api.traces.createTraces>;
  let updateTracesSpy: MockInstance<typeof client.api.traces.updateTrace>;

  beforeEach(() => {
    client = new Opik({
      projectName: "opik-sdk-typescript",
    });
    sdk = new NodeSDK({
      traceExporter: new OpikExporter({ client }),
      instrumentations: [getNodeAutoInstrumentations()],
    });

    createSpansSpy = vi
      .spyOn(client.api.spans, "createSpans")
      .mockImplementation(mockAPIPromise);

    updateSpansSpy = vi
      .spyOn(client.api.spans, "updateSpan")
      .mockImplementation(mockAPIPromise);

    createTracesSpy = vi
      .spyOn(client.api.traces, "createTraces")
      .mockImplementation(mockAPIPromise);

    updateTracesSpy = vi
      .spyOn(client.api.traces, "updateTrace")
      .mockImplementation(mockAPIPromise);

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
      model: new MockLanguageModelV1({
        doGenerate: async () => ({
          rawCall: { rawPrompt: null, rawSettings: {} },
          finishReason: "stop",
          usage: { promptTokens: 10, completionTokens: 20 },
          text: output,
        }),
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
});
