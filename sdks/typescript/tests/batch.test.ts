import { Opik } from "@opik";
import { MockInstance } from "vitest";
import { advanceToDelay } from "./utils";

const logTraceAndSpan = async ({
  client,
  delay,
  spanAmount = 5,
  traceAmount = 5,
}: {
  client: Opik;
  delay?: number;
  spanAmount?: number;
  traceAmount?: number;
}) => {
  for (let i = 0; i < traceAmount; i++) {
    const someTrace = client.trace({
      name: `test-${i}`,
    });

    for (let j = 0; j < spanAmount; j++) {
      const someSpan = someTrace.span({
        name: `test-${i}-span-${j}`,
        type: "llm",
      });

      if (delay) {
        await advanceToDelay(delay);
      }

      someSpan.end();
    }

    someTrace.end();
  }

  if (delay) {
    await advanceToDelay(delay);
  }

  await client.flush();
};

async function mockAPIPromise<T>() {
  return {} as T;
}

describe("Opik client batching", () => {
  let client: Opik;
  let createSpansSpy: MockInstance<typeof client.api.spans.createSpans>;
  let updateSpansSpy: MockInstance<typeof client.api.spans.updateSpan>;
  let createTracesSpy: MockInstance<typeof client.api.traces.createTraces>;
  let updateTracesSpy: MockInstance<typeof client.api.traces.updateTrace>;

  beforeEach(() => {
    client = new Opik({
      projectName: "opik-sdk-typescript",
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

  it("basic create and update with flush flow - merge entity locally", async () => {
    const trace = client.trace({ name: "test" });
    trace.end();
    await client.flush();

    expect(createTracesSpy).toHaveBeenCalledTimes(1);
    expect(updateTracesSpy).toHaveBeenCalledTimes(0);
  });

  it("basic create and update with flush flow", async () => {
    const trace = client.trace({ name: "test" });
    await client.flush();
    trace.end();
    await client.flush();

    expect(createTracesSpy).toHaveBeenCalledTimes(1);
    expect(updateTracesSpy).toHaveBeenCalledTimes(1);
  });

  it("should log traces and spans in batches", async () => {
    await logTraceAndSpan({ client });

    expect(createTracesSpy).toHaveBeenCalledTimes(1);
    expect(createSpansSpy).toHaveBeenCalledTimes(1);
    expect(updateSpansSpy).toHaveBeenCalledTimes(0);
    expect(updateTracesSpy).toHaveBeenCalledTimes(0);
  });

  it("should log traces and spans - one call per trace, batch per span (<300ms)", async () => {
    await logTraceAndSpan({ client, delay: 200 });

    expect(createTracesSpy).toHaveBeenCalledTimes(5);
    expect(createSpansSpy).toHaveBeenCalledTimes(1);
    expect(updateSpansSpy).toHaveBeenCalledTimes(0);
    expect(updateTracesSpy).toHaveBeenCalledTimes(5);
  });

  it("should log traces and spans - one call per each entity (>300ms)", async () => {
    await logTraceAndSpan({ client, delay: 1000 });

    expect(createTracesSpy).toHaveBeenCalledTimes(5);
    expect(createSpansSpy).toHaveBeenCalledTimes(25);
    expect(updateSpansSpy).toHaveBeenCalledTimes(25);
    expect(updateTracesSpy).toHaveBeenCalledTimes(5);
  });
});
