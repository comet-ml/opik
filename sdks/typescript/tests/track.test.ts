import { getTrackOpikClient } from "@/decorators/track";
import {
  getTrackContext,
  resetTracingToConfigDefault,
  setTracingActive,
  track,
} from "opik";
import { MockInstance } from "vitest";
import { advanceToDelay } from "./utils";
import { mockAPIFunction } from "./mockUtils";

describe("Track decorator", () => {
  let trackOpikClient: ReturnType<typeof getTrackOpikClient>;
  let createSpansSpy: MockInstance<
    typeof trackOpikClient.api.spans.createSpans
  >;
  let updateSpansSpy: MockInstance<typeof trackOpikClient.api.spans.updateSpan>;
  let createTracesSpy: MockInstance<
    typeof trackOpikClient.api.traces.createTraces
  >;
  let updateTracesSpy: MockInstance<
    typeof trackOpikClient.api.traces.updateTrace
  >;

  beforeEach(() => {
    trackOpikClient = getTrackOpikClient();

    createSpansSpy = vi
      .spyOn(trackOpikClient.api.spans, "createSpans")
      .mockImplementation(mockAPIFunction);

    updateSpansSpy = vi
      .spyOn(trackOpikClient.api.spans, "updateSpan")
      .mockImplementation(mockAPIFunction);

    createTracesSpy = vi
      .spyOn(trackOpikClient.api.traces, "createTraces")
      .mockImplementation(mockAPIFunction);

    updateTracesSpy = vi
      .spyOn(trackOpikClient.api.traces, "updateTrace")
      .mockImplementation(mockAPIFunction);

    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.useRealTimers();

    createSpansSpy.mockRestore();
    createTracesSpy.mockRestore();
    updateSpansSpy.mockRestore();
    updateTracesSpy.mockRestore();
  });

  it("should maintain correct span hierarchy for mixed async/sync functions", async () => {
    const f111 = track({ name: "innerf111" }, () => "f111");
    const f11 = track(async function innerf11(a: number, b: number) {
      await advanceToDelay(10);
      f111();
      return a + b;
    });
    const f12 = track(function innerf12() {
      return "f12";
    });
    const f13 = track({ name: "innerf13" }, () => ({ hello: "world" }));
    const f1 = track({ projectName: "with-track" }, async function innerf1() {
      const promise = f11(1, 2);
      f12();
      f13({ a: "b" });
      return promise;
    });

    await f1("test:f1");
    await trackOpikClient.flush();

    expect(createTracesSpy).toHaveBeenCalledTimes(1);
    expect(createSpansSpy).toHaveBeenCalledTimes(1);

    const spans = createSpansSpy.mock.calls
      .map((call) => call?.[0]?.spans ?? [])
      .flat();

    expect(spans[0]).toMatchObject({
      name: "innerf1",
      parentSpanId: undefined,
    });
    expect(spans[1]).toMatchObject({
      name: "innerf11",
      input: { arguments: [1, 2] },
      output: { result: 3 },
      parentSpanId: spans[0]?.id,
    });
    expect(spans[2]).toMatchObject({
      name: "innerf12",
      parentSpanId: spans[0]?.id,
    });
    expect(spans[3]).toMatchObject({
      name: "innerf13",
      parentSpanId: spans[0]?.id,
    });
    expect(spans[4]).toMatchObject({
      name: "innerf111",
      parentSpanId: spans[1]?.id,
    });
  });

  it("should run the bare function without tracing when tracking is disabled", async () => {
    setTracingActive(false);

    try {
      let contextInsideTrack: ReturnType<typeof getTrackContext>;
      const inner = track({ name: "inner" }, () => {
        contextInsideTrack = getTrackContext();
        return "inner-result";
      });
      const outer = track(async function outer() {
        return inner();
      });

      const result = await outer();
      await trackOpikClient.flush();

      // The wrapped function still runs, but the decorator does not create any
      // trace/span context and sends nothing to the backend (matches Python).
      expect(result).toBe("inner-result");
      expect(contextInsideTrack).toBeUndefined();
      expect(createTracesSpy).not.toHaveBeenCalled();
      expect(createSpansSpy).not.toHaveBeenCalled();
      expect(updateTracesSpy).not.toHaveBeenCalled();
      expect(updateSpansSpy).not.toHaveBeenCalled();
    } finally {
      resetTracingToConfigDefault();
    }
  });

  it("should resume tracing after setTracingActive(true)", async () => {
    setTracingActive(false);

    try {
      const disabled = track(() => "x");
      await disabled();

      setTracingActive(true);

      const enabled = track({ name: "enabled" }, () => "y");
      await enabled();
      await trackOpikClient.flush();

      expect(createTracesSpy).toHaveBeenCalledTimes(1);
      expect(createSpansSpy).toHaveBeenCalledTimes(1);
    } finally {
      resetTracingToConfigDefault();
    }
  });

  it("should not send data for client.trace()/span() when tracking is disabled", async () => {
    setTracingActive(false);

    try {
      const trace = trackOpikClient.trace({ name: "direct" });
      const span = trace.span({ name: "direct-span" });
      span.end();
      trace.end();
      await trackOpikClient.flush();

      // The gate lives in the core primitives, so every tracing path (the
      // decorator, integrations, and direct client calls) stops sending.
      expect(createTracesSpy).not.toHaveBeenCalled();
      expect(createSpansSpy).not.toHaveBeenCalled();
    } finally {
      resetTracingToConfigDefault();
    }
  });

  it("track decorator (class methods)", async () => {
    class TestClass {
      @track({ type: "llm" })
      async llmCall() {
        await advanceToDelay(5000);
        return "llm result";
      }

      @track({ name: "translate" })
      async translate(text: string) {
        await advanceToDelay(1000);
        return `translated: ${text}`;
      }

      @track({ name: "initial", projectName: "track-decorator-test" })
      async execute() {
        const result = await this.llmCall();
        return this.translate(result);
      }
    }

    const test = new TestClass();
    await test.execute();
    await trackOpikClient.flush();

    expect(createTracesSpy).toHaveBeenCalledTimes(1);
    expect(createSpansSpy).toHaveBeenCalledTimes(2);
    expect(updateSpansSpy).toHaveBeenCalledTimes(3);
    expect(updateTracesSpy).toHaveBeenCalledTimes(1);

    const spans = createSpansSpy.mock.calls
      .map((call) => call?.[0]?.spans ?? [])
      .flat();

    expect(spans[0]).toMatchObject({
      name: "initial",
      parentSpanId: undefined,
    });
    expect(spans[1]).toMatchObject({
      name: "llmCall",
      parentSpanId: spans[0]?.id,
    });
    expect(spans[2]).toMatchObject({
      name: "translate",
      parentSpanId: spans[0]?.id,
    });
  });

  it("tracked function can access to its context", async () => {
    const llmCall = track(
      { name: "llm-test", type: "llm" },
      async () => "llm result"
    );

    const translate = track(
      { name: "translate", type: "tool" },
      async (text) => {
        const context = getTrackContext();

        if (context?.span) {
          context.span.update({
            tags: ["translate-tag"],
          });
        }

        return `translated: ${text}`;
      }
    );

    const execute = track(
      { name: "initial", projectName: "track-decorator-test" },
      async () => {
        const result = await llmCall();
        return translate(result);
      }
    );

    await execute();
    await trackOpikClient.flush();

    expect(createTracesSpy).toHaveBeenCalledTimes(1);
    expect(createSpansSpy).toHaveBeenCalledTimes(1);

    const spans = createSpansSpy.mock.calls
      .map((call) => call?.[0]?.spans ?? [])
      .flat();

    expect(spans[2]).toMatchObject({
      tags: ["translate-tag"],
    });
  });
});
