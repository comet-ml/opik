import {
  runnerJobStorage,
  getRunnerJobContext,
  getPresetTraceId,
  runWithJobContext,
} from "@/runner/context";

describe("runner job context", () => {
  it("returns undefined outside any context", () => {
    expect(getRunnerJobContext()).toBeUndefined();
    expect(getPresetTraceId()).toBeUndefined();
  });

  it("propagates traceId and jobId within the callback", () => {
    let captured: ReturnType<typeof getRunnerJobContext>;
    let capturedTraceId: string | undefined;

    runWithJobContext({ traceId: "trace-123", jobId: "job-456" }, () => {
      captured = getRunnerJobContext();
      capturedTraceId = getPresetTraceId();
    });

    expect(captured!).toEqual({ traceId: "trace-123", jobId: "job-456" });
    expect(capturedTraceId).toBe("trace-123");
  });

  it("returns undefined traceId when not set", () => {
    let capturedTraceId: string | undefined;

    runWithJobContext({ jobId: "job-789" }, () => {
      capturedTraceId = getPresetTraceId();
    });

    expect(capturedTraceId).toBeUndefined();
  });

  it("isolates nested contexts", () => {
    let outer: string | undefined;
    let inner: string | undefined;
    let afterInner: string | undefined;

    runWithJobContext({ traceId: "outer-trace", jobId: "outer-job" }, () => {
      outer = getPresetTraceId();
      runWithJobContext({ traceId: "inner-trace", jobId: "inner-job" }, () => {
        inner = getPresetTraceId();
      });
      afterInner = getPresetTraceId();
    });

    expect(outer).toBe("outer-trace");
    expect(inner).toBe("inner-trace");
    expect(afterInner).toBe("outer-trace");
  });

  it("works with async callbacks", async () => {
    let captured: string | undefined;

    await runWithJobContext({ traceId: "async-trace", jobId: "async-job" }, async () => {
      await new Promise((resolve) => setTimeout(resolve, 1));
      captured = getPresetTraceId();
    });

    expect(captured).toBe("async-trace");
  });

  it("does not leak context after callback completes", () => {
    runWithJobContext({ traceId: "temp", jobId: "temp" }, () => {
      // inside context
    });

    expect(getRunnerJobContext()).toBeUndefined();
  });

  it("exposes storage directly for advanced usage", () => {
    let storeValue: unknown;

    runnerJobStorage.run({ traceId: "direct", jobId: "direct-job" }, () => {
      storeValue = runnerJobStorage.getStore();
    });

    expect(storeValue).toEqual({ traceId: "direct", jobId: "direct-job" });
  });
});
