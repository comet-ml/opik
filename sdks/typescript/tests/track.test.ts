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

describe("@track with non-Error thrown values", () => {
  let trackOpikClient: ReturnType<typeof getTrackOpikClient>;
  let createTracesSpy: MockInstance;
  let createSpansSpy: MockInstance;
  let updateTracesSpy: MockInstance;
  let updateSpansSpy: MockInstance;

  const NOT_THROWN = Symbol("call did not throw");

  // `super(message)` would create an own `message` data property that shadows a prototype
  // getter, so the throwing accessor has to sit on the instance itself.
  const throwingMessageError = () => {
    const error = new Error("original message");
    error.name = "ClassyBoom";
    Object.defineProperty(error, "message", {
      get(): never {
        throw new Error("message getter exploded");
      },
    });
    return error;
  };

  beforeEach(() => {
    trackOpikClient = getTrackOpikClient();
    createTracesSpy = vi
      .spyOn(trackOpikClient.api.traces, "createTraces")
      .mockImplementation(mockAPIFunction);
    updateTracesSpy = vi
      .spyOn(trackOpikClient.api.traces, "updateTrace")
      .mockImplementation(mockAPIFunction);
    createSpansSpy = vi
      .spyOn(trackOpikClient.api.spans, "createSpans")
      .mockImplementation(mockAPIFunction);
    updateSpansSpy = vi
      .spyOn(trackOpikClient.api.spans, "updateSpan")
      .mockImplementation(mockAPIFunction);
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.useRealTimers();
    createTracesSpy.mockRestore();
    updateTracesSpy.mockRestore();
    createSpansSpy.mockRestore();
    updateSpansSpy.mockRestore();
  });

  const nonErrorFailures: [string, unknown][] = [
    ["undefined", undefined],
    ["null", null],
    ["string", "boom"],
    ["object", { code: 42 }],
  ];

  const failRootSpan = async (thrown: unknown, mode: "sync" | "async") => {
    createTracesSpy.mockClear();
    createSpansSpy.mockClear();

    const failing = track(
      { name: "throws-non-error" },
      mode === "async"
        ? async () => Promise.reject(thrown)
        : () => {
            throw thrown;
          }
    );

    let caught: unknown = NOT_THROWN;
    try {
      await failing();
    } catch (error) {
      caught = error;
    }
    await trackOpikClient.flush();

    return {
      caught,
      trace: createTracesSpy.mock.calls[0]?.[0]?.traces?.[0],
      span: createSpansSpy.mock.calls[0]?.[0]?.spans?.[0],
    };
  };

  it.each(nonErrorFailures)(
    "hands the thrown %s back to the caller unchanged",
    async (_label, thrown) => {
      const { caught } = await failRootSpan(thrown, "sync");
      expect(caught).toBe(thrown);
    }
  );

  it.each(nonErrorFailures)(
    "closes and reports the trace for a non-Error %s failure",
    async (_label, thrown) => {
      const { trace, span } = await failRootSpan(thrown, "sync");

      expect(createSpansSpy).toHaveBeenCalledTimes(1);
      expect(createTracesSpy).toHaveBeenCalledTimes(1);
      expect(span?.endTime).toBeInstanceOf(Date);
      expect(trace?.endTime).toBeInstanceOf(Date);
      expect(trace?.errorInfo).toEqual(span?.errorInfo);
      expect(trace?.errorInfo?.message).toBe(String(thrown));
      expect(typeof trace?.errorInfo?.exceptionType).toBe("string");
      expect(typeof trace?.errorInfo?.traceback).toBe("string");
    }
  );

  it.each(nonErrorFailures)(
    "hands a rejected promise of %s back to the caller and closes the trace",
    async (_label, thrown) => {
      const { caught, trace } = await failRootSpan(thrown, "async");

      expect(caught).toBe(thrown);
      expect(trace?.endTime).toBeInstanceOf(Date);
    }
  );

  it.each([
    ["an object with no usable toString", Object.create(null)],
    ["a throwing toString", { toString: () => {
        throw new Error("toString exploded");
      } }],
    ["an Error whose message getter throws", throwingMessageError()],
  ] as [string, unknown][])(
    "still hands back %s and closes both entities",
    async (_label, thrown) => {
      const { caught, trace, span } = await failRootSpan(thrown, "sync");

      expect(caught).toBe(thrown);
      expect(span?.endTime).toBeInstanceOf(Date);
      expect(trace?.endTime).toBeInstanceOf(Date);
      expect(typeof trace?.errorInfo?.message).toBe("string");
      expect(trace?.errorInfo?.message).not.toBe("");
      expect(typeof trace?.errorInfo?.exceptionType).toBe("string");
      expect(trace?.errorInfo).toEqual(span?.errorInfo);
    }
  );

  it("keeps reporting a real Error the way it did before", async () => {
    const failure = new TypeError("bad-arg");
    failure.name = "CustomTypeError";
    const { caught, trace, span } = await failRootSpan(failure, "sync");

    expect(caught).toBe(failure);
    expect(span?.errorInfo).toMatchObject({
      message: "bad-arg",
      exceptionType: "CustomTypeError",
    });
    expect((span?.errorInfo?.traceback as string).length).toBeGreaterThan(0);
    expect(span?.errorInfo?.traceback).toContain("bad-arg");
    expect(trace?.errorInfo).toEqual(span?.errorInfo);
    expect(trace?.endTime).toBeInstanceOf(Date);
  });
});
