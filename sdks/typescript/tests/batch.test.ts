import { logger } from "@/utils/logger";
import { Opik } from "opik";
import { MockInstance } from "vitest";
import { advanceToDelay } from "./utils";
import { mockAPIFunction, mockAPIFunctionWithError } from "./mockUtils";

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

describe("Opik client batching", () => {
  let client: Opik;
  let createSpansSpy: MockInstance<typeof client.api.spans.createSpans>;
  let updateSpansSpy: MockInstance<typeof client.api.spans.updateSpan>;
  let createTracesSpy: MockInstance<typeof client.api.traces.createTraces>;
  let updateTracesSpy: MockInstance<typeof client.api.traces.updateTrace>;
  let loggerErrorSpy: MockInstance<typeof logger.error>;

  beforeEach(() => {
    client = new Opik({
      projectName: "opik-sdk-typescript",
    });

    createSpansSpy = vi
      .spyOn(client.api.spans, "createSpans")
      .mockImplementation(mockAPIFunction);

    updateSpansSpy = vi
      .spyOn(client.api.spans, "updateSpan")
      .mockImplementation(mockAPIFunction);

    createTracesSpy = vi
      .spyOn(client.api.traces, "createTraces")
      .mockImplementation(mockAPIFunction);

    updateTracesSpy = vi
      .spyOn(client.api.traces, "updateTrace")
      .mockImplementation(mockAPIFunction);

    loggerErrorSpy = vi.spyOn(logger, "error");

    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.useRealTimers();

    createSpansSpy.mockRestore();
    createTracesSpy.mockRestore();
    updateSpansSpy.mockRestore();
    updateTracesSpy.mockRestore();
    loggerErrorSpy.mockRestore();
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

  it("should log an error if trace endpoint fails", async () => {
    const errorMessage = "Test error";

    createTracesSpy.mockImplementation(mockAPIFunctionWithError(errorMessage));

    const trace = client.trace({ name: "test" });
    trace.end();
    await client.flush();

    expect(createTracesSpy).toHaveBeenCalledTimes(1);
    expect(loggerErrorSpy).toHaveBeenCalledTimes(1);
    expect(loggerErrorSpy.mock.calls[0].flat().toString()).toContain(
      errorMessage
    );
  });

  it("should merge multiple trace updates after flush", async () => {
    const trace = client.trace({ name: "test" });
    await client.flush(); // Flush create

    trace.update({ output: "result" });
    trace.end(); // Calls update({ endTime })
    await client.flush();

    expect(createTracesSpy).toHaveBeenCalledTimes(1);
    expect(updateTracesSpy).toHaveBeenCalledTimes(1);

    // Verify the update call contains both output AND endTime
    const updateCall = updateTracesSpy.mock.calls[0];
    expect(updateCall[1].body).toHaveProperty("output", "result");
    expect(updateCall[1].body).toHaveProperty("endTime");
    expect(loggerErrorSpy).toHaveBeenCalledTimes(0);
  });

  it("should merge multiple span updates after flush", async () => {
    const trace = client.trace({ name: "test" });
    const span = trace.span({ name: "test-span", type: "llm" });
    await client.flush();

    span.update({ output: "span-result" });
    span.end();
    await client.flush();

    expect(updateSpansSpy).toHaveBeenCalledTimes(1);
    const updateCall = updateSpansSpy.mock.calls[0];
    expect(updateCall[1].body).toHaveProperty("output", "span-result");
    expect(updateCall[1].body).toHaveProperty("endTime");
    expect(loggerErrorSpy).toHaveBeenCalledTimes(0);
  });

  it("should preserve all data in rapid successive updates", async () => {
    const trace = client.trace({ name: "test" });
    await client.flush();

    trace.update({ input: "input-data" });
    trace.update({ output: "output-data" });
    trace.update({ metadata: { key: "value" } });
    trace.end();
    await client.flush();

    expect(updateTracesSpy).toHaveBeenCalledTimes(1);
    const updateCall = updateTracesSpy.mock.calls[0];
    expect(updateCall[1].body).toHaveProperty("input", "input-data");
    expect(updateCall[1].body).toHaveProperty("output", "output-data");
    expect(updateCall[1].body.metadata).toEqual({ key: "value" });
    expect(updateCall[1].body).toHaveProperty("endTime");
    expect(loggerErrorSpy).toHaveBeenCalledTimes(0);
  });

  it("should merge metadata from update() with initial metadata", async () => {
    const trace = client.trace({
      name: "test",
      metadata: { initial: "yes" },
    });
    await client.flush();

    trace.update({ metadata: { updated: "yes" } });
    trace.end();
    await client.flush();

    expect(updateTracesSpy).toHaveBeenCalledTimes(1);
    const updateCall = updateTracesSpy.mock.calls[0];
    expect(updateCall[1].body.metadata).toEqual({
      initial: "yes",
      updated: "yes",
    });
    expect(loggerErrorSpy).toHaveBeenCalledTimes(0);
  });

  it("should persist metadata set only via update() on trace", async () => {
    const trace = client.trace({ name: "test" });
    await client.flush();

    trace.update({ metadata: { source: "from_update" } });
    trace.end();
    await client.flush();

    expect(updateTracesSpy).toHaveBeenCalledTimes(1);
    const updateCall = updateTracesSpy.mock.calls[0];
    expect(updateCall[1].body.metadata).toEqual({ source: "from_update" });
    expect(loggerErrorSpy).toHaveBeenCalledTimes(0);
  });

  it("should merge metadata from update() with initial metadata on span", async () => {
    const trace = client.trace({ name: "test" });
    const span = trace.span({
      name: "test-span",
      type: "llm",
      metadata: { initial: "yes" },
    });
    await client.flush();

    span.update({ metadata: { updated: "yes" } });
    span.end();
    await client.flush();

    expect(updateSpansSpy).toHaveBeenCalledTimes(1);
    const updateCall = updateSpansSpy.mock.calls[0];
    expect(updateCall[1].body.metadata).toEqual({
      initial: "yes",
      updated: "yes",
    });
    expect(loggerErrorSpy).toHaveBeenCalledTimes(0);
  });
});
