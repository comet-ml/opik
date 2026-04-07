import {
  getTrackOpikClient,
  _resetTrackOpikClientCache,
  track,
} from "@/decorators/track";
import { getAll } from "@/runner/registry";
import { getPresetTraceId, runWithJobContext } from "@/runner/context";
import { logger } from "@/utils/logger";
import { MockInstance } from "vitest";
import { mockAPIFunction } from "@tests/mockUtils";

describe("track with entrypoint", () => {
  let trackOpikClient: ReturnType<typeof getTrackOpikClient>;
  let createSpansSpy: MockInstance;
  let updateSpansSpy: MockInstance;
  let createTracesSpy: MockInstance;
  let updateTracesSpy: MockInstance;

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

  it("registers function in the runner registry when entrypoint=true", () => {
    const fn = track(
      { entrypoint: true, name: "my-entrypoint-agent" },
      async (msg: string) => `reply: ${msg}`
    );

    expect(typeof fn).toBe("function");

    const all = getAll();
    const entry = all.get("my-entrypoint-agent");
    expect(entry).toBeDefined();
    expect(entry!.name).toBe("my-entrypoint-agent");
    expect(entry!.params).toEqual([{ name: "msg", type: "string" }]);
  });

  it("still traces execution normally when entrypoint=true", async () => {
    const fn = track(
      { entrypoint: true, name: "traced-entrypoint" },
      async (x: string) => `result: ${x}`
    );

    const result = await fn("test");
    await trackOpikClient.flush();

    expect(result).toBe("result: test");
    expect(createTracesSpy).toHaveBeenCalled();
    expect(createSpansSpy).toHaveBeenCalled();
  });

  it("uses function name when track name is not provided", () => {
    async function myNamedAgent(input: string) {
      return input;
    }

    track({ entrypoint: true }, myNamedAgent);

    const all = getAll();
    expect(all.has("myNamedAgent")).toBe(true);
  });

  it("throws when entrypoint=true and no name can be determined", () => {
    expect(() => {
      track({ entrypoint: true }, async (x: string) => x);
    }).toThrow(/entrypoint functions must have a name/);
  });

  it("uses explicit params when provided, ignoring function source", () => {
    const fn = track(
      {
        entrypoint: true,
        name: "explicit-params-agent",
        params: [
          { name: "query", type: "string" },
          { name: "limit", type: "number" },
        ],
      },
      async (a: string, b: number) => `${a}:${b}`
    );

    expect(typeof fn).toBe("function");
    const entry = getAll().get("explicit-params-agent");
    expect(entry!.params).toEqual([
      { name: "query", type: "string" },
      { name: "limit", type: "float" },
    ]);
  });

  it("falls back to extractParams when params not provided", () => {
    track(
      { entrypoint: true, name: "fallback-params-agent" },
      async (userId: string) => userId
    );

    const entry = getAll().get("fallback-params-agent");
    expect(entry!.params).toEqual([{ name: "userId", type: "string" }]);
  });

  it("warns when explicit params use unsupported types", () => {
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    const warnSpy = vi.spyOn(logger, "warn").mockImplementation((() => undefined) as any);

    track(
      {
        entrypoint: true,
        name: "unsupported-types-agent",
        params: [
          { name: "query", type: "string" },
          { name: "first_custom", type: "object" },
          { name: "second_custom", type: "MyClass" },
        ],
      },
      async (a: string) => a
    );

    expect(warnSpy).toHaveBeenCalledTimes(1);
    const warning = warnSpy.mock.calls[0][0] as string;
    expect(warning).toContain("first_custom");
    expect(warning).toContain("second_custom");
    expect(warning).not.toContain("query");

    warnSpy.mockRestore();
  });

  it("does not register when entrypoint is not set", () => {
    const _fn = track(
      { name: "not-an-entrypoint" },
      async (x: string) => x
    );

    expect(typeof _fn).toBe("function");
    expect(getAll().has("not-an-entrypoint")).toBe(false);
  });

  it("registered function produces traces when called", async () => {
    track(
      { entrypoint: true, name: "callable-entrypoint" },
      async (msg: string) => `echo: ${msg}`
    );

    const entry = getAll().get("callable-entrypoint");
    expect(entry).toBeDefined();

    // Call the registered function (as the runner would)
    const result = await entry!.func("hello");
    await trackOpikClient.flush();

    expect(result).toBe("echo: hello");
    expect(createTracesSpy).toHaveBeenCalled();
  });
});

describe("trace ID injection via runner context", () => {
  let trackOpikClient: ReturnType<typeof getTrackOpikClient>;
  let createTracesSpy: MockInstance;
  let createSpansSpy: MockInstance;
  let updateSpansSpy: MockInstance;
  let updateTracesSpy: MockInstance;

  beforeEach(() => {
    trackOpikClient = getTrackOpikClient();

    createTracesSpy = vi
      .spyOn(trackOpikClient.api.traces, "createTraces")
      .mockImplementation(mockAPIFunction);

    createSpansSpy = vi
      .spyOn(trackOpikClient.api.spans, "createSpans")
      .mockImplementation(mockAPIFunction);

    updateSpansSpy = vi
      .spyOn(trackOpikClient.api.spans, "updateSpan")
      .mockImplementation(mockAPIFunction);

    updateTracesSpy = vi
      .spyOn(trackOpikClient.api.traces, "updateTrace")
      .mockImplementation(mockAPIFunction);

    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.useRealTimers();
    createTracesSpy.mockRestore();
    createSpansSpy.mockRestore();
    updateSpansSpy.mockRestore();
    updateTracesSpy.mockRestore();
  });

  it("uses preset trace ID when running inside runner job context", async () => {
    const fn = track(
      { name: "trace-id-test" },
      async () => "done"
    );

    await runWithJobContext(
      { traceId: "preset-trace-abc", jobId: "job-1" },
      () => fn()
    );
    await trackOpikClient.flush();

    expect(createTracesSpy).toHaveBeenCalled();
    const traces = createTracesSpy.mock.calls
      .map((call: unknown[]) => {
        const arg = call[0] as { traces?: { id?: string }[] };
        return arg?.traces ?? [];
      })
      .flat();

    expect(traces[0]?.id).toBe("preset-trace-abc");
  });

  it("generates a new trace ID when NOT in runner context", async () => {
    const fn = track(
      { name: "no-runner-context" },
      async () => "done"
    );

    await fn();
    await trackOpikClient.flush();

    expect(createTracesSpy).toHaveBeenCalled();
    const traces = createTracesSpy.mock.calls
      .map((call: unknown[]) => {
        const arg = call[0] as { traces?: { id?: string }[] };
        return arg?.traces ?? [];
      })
      .flat();

    // Should have a generated ID, not "preset-trace-abc"
    expect(traces[0]?.id).toBeDefined();
    expect(traces[0]?.id).not.toBe("preset-trace-abc");
  });

  it("getPresetTraceId returns undefined outside context", () => {
    expect(getPresetTraceId()).toBeUndefined();
  });
});
