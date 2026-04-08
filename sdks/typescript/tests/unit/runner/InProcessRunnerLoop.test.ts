import { InProcessRunnerLoop } from "@/runner/InProcessRunnerLoop";
import { register } from "@/runner/registry";
import { createMockHttpResponsePromise } from "@tests/mockUtils";
import type { OpikApiClientTemp } from "@/client/OpikApiClientTemp";
import type { LocalRunnerJob } from "@/rest_api/api/types/LocalRunnerJob";
import { GoneError } from "@/rest_api/api/errors/GoneError";

function createMockApi() {
  return {
    runners: {
      heartbeat: vi.fn(() =>
        createMockHttpResponsePromise({ cancelledJobIds: [] })
      ),
      nextJob: vi.fn(() => createMockHttpResponsePromise(null)),
      reportJobResult: vi.fn(() => createMockHttpResponsePromise(undefined)),
      registerAgents: vi.fn(() => createMockHttpResponsePromise(undefined)),
      appendJobLogs: vi.fn(() => createMockHttpResponsePromise(undefined)),
    },
  } as unknown as OpikApiClientTemp;
}

function createJob(overrides: Partial<LocalRunnerJob> = {}): LocalRunnerJob {
  return {
    id: "job-1",
    runnerId: "runner-1",
    agentName: "test-agent",
    status: "pending",
    inputs: { message: "hello" },
    traceId: "trace-1",
    maskId: undefined,
    timeout: undefined,
    ...overrides,
  };
}

describe("InProcessRunnerLoop", () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  it("starts and shuts down without errors", () => {
    const api = createMockApi();
    const loop = new InProcessRunnerLoop(api, "runner-1");

    expect(() => loop.start()).not.toThrow();
    expect(() => loop.shutdown()).not.toThrow();
  });

  it("sends heartbeats on interval", async () => {
    const api = createMockApi();
    const loop = new InProcessRunnerLoop(api, "runner-1", {
      heartbeatIntervalMs: 100,
    });

    loop.start();

    await vi.advanceTimersByTimeAsync(350);

    loop.shutdown();

    expect(api.runners.heartbeat).toHaveBeenCalledWith("runner-1");
    expect(
      (api.runners.heartbeat as ReturnType<typeof vi.fn>).mock.calls.length
    ).toBeGreaterThanOrEqual(2);
  });

  it("shuts down on 410 Gone heartbeat response", async () => {
    const api = createMockApi();
    (api.runners.heartbeat as ReturnType<typeof vi.fn>).mockImplementation(
      () => {
        throw new GoneError(
          { code: 410, message: "Runner deregistered" },
          { status: 410 } as Response
        );
      }
    );

    const loop = new InProcessRunnerLoop(api, "runner-1", {
      heartbeatIntervalMs: 50,
    });

    loop.start();
    await vi.advanceTimersByTimeAsync(100);

    // After shutdown, no more heartbeats should be sent
    const callCount = (api.runners.heartbeat as ReturnType<typeof vi.fn>).mock
      .calls.length;
    await vi.advanceTimersByTimeAsync(200);
    expect(
      (api.runners.heartbeat as ReturnType<typeof vi.fn>).mock.calls.length
    ).toBe(callCount);
  });

  it("executes a job and reports success", async () => {
    vi.useRealTimers();

    const api = createMockApi();
    const job = createJob();

    register({
      func: (message: string) => `echo: ${message}`,
      name: "test-agent",
      project: "default",
      params: [{ name: "message", type: "string" }],
      docstring: "",
    });

    let callCount = 0;
    (api.runners.nextJob as ReturnType<typeof vi.fn>).mockImplementation(
      () => {
        callCount++;
        if (callCount === 1) {
          return createMockHttpResponsePromise(job);
        }
        return createMockHttpResponsePromise(null);
      }
    );

    const loop = new InProcessRunnerLoop(api, "runner-1");
    loop.start();

    // Wait for the job to be polled and executed
    await new Promise((resolve) => setTimeout(resolve, 500));
    loop.shutdown();

    const calls = (api.runners.reportJobResult as ReturnType<typeof vi.fn>).mock.calls;
    const runningCall = calls.find((c: unknown[]) => (c[1] as { status: string }).status === "running");
    const completedCall = calls.find((c: unknown[]) => (c[1] as { status: string }).status === "completed");
    expect(runningCall).toBeDefined();
    expect(completedCall).toBeDefined();
    // traceId is generated client-side and shared between running and completed
    expect(runningCall![1].traceId).toEqual(completedCall![1].traceId);
    expect(typeof runningCall![1].traceId).toBe("string");
    expect(completedCall![1].result).toEqual({ result: "echo: hello" });
  });

  it("reports running before invoking the agent function", async () => {
    const api = createMockApi();
    const job = createJob({ agentName: "ordered-agent" });

    const callOrder: string[] = [];

    (api.runners.reportJobResult as ReturnType<typeof vi.fn>).mockImplementation(
      (_jobId: string, payload: { status: string }) => {
        callOrder.push(payload.status);
        return createMockHttpResponsePromise(undefined);
      }
    );

    register({
      func: () => {
        callOrder.push("func");
        return "done";
      },
      name: "ordered-agent",
      project: "default",
      params: [],
      docstring: "",
    });

    let callCount = 0;
    (api.runners.nextJob as ReturnType<typeof vi.fn>).mockImplementation(
      () => {
        callCount++;
        if (callCount === 1) {
          return createMockHttpResponsePromise(job);
        }
        return createMockHttpResponsePromise(null);
      }
    );

    const loop = new InProcessRunnerLoop(api, "runner-1");
    loop.start();

    // Drain microtasks — the first poll fires immediately (no timer delay),
    // picks up the job, and runs executeJob entirely via resolved promises.
    await vi.advanceTimersByTimeAsync(0);
    loop.shutdown();

    expect(callOrder).toEqual(["running", "func", "completed"]);
  });

  it("reports failure for unknown agent", async () => {
    vi.useRealTimers();

    const api = createMockApi();
    const job = createJob({ agentName: "nonexistent-agent" });

    let callCount = 0;
    (api.runners.nextJob as ReturnType<typeof vi.fn>).mockImplementation(
      () => {
        callCount++;
        if (callCount === 1) {
          return createMockHttpResponsePromise(job);
        }
        return createMockHttpResponsePromise(null);
      }
    );

    const loop = new InProcessRunnerLoop(api, "runner-1");
    loop.start();

    await new Promise((resolve) => setTimeout(resolve, 500));
    loop.shutdown();

    expect(api.runners.reportJobResult).toHaveBeenCalledWith(
      "job-1",
      expect.objectContaining({
        status: "failed",
        error: "Unknown agent: nonexistent-agent",
        traceId: expect.any(String),
      })
    );
  });

  it("reports failure when entrypoint throws", async () => {
    vi.useRealTimers();

    const api = createMockApi();
    const job = createJob({ agentName: "failing-agent" });

    register({
      func: () => {
        throw new Error("something broke");
      },
      name: "failing-agent",
      project: "default",
      params: [{ name: "message", type: "string" }],
      docstring: "",
    });

    let callCount = 0;
    (api.runners.nextJob as ReturnType<typeof vi.fn>).mockImplementation(
      () => {
        callCount++;
        if (callCount === 1) {
          return createMockHttpResponsePromise(job);
        }
        return createMockHttpResponsePromise(null);
      }
    );

    const loop = new InProcessRunnerLoop(api, "runner-1");
    loop.start();

    await new Promise((resolve) => setTimeout(resolve, 500));
    loop.shutdown();

    expect(api.runners.reportJobResult).toHaveBeenCalledWith(
      "job-1",
      expect.objectContaining({
        status: "failed",
        error: "Error: something broke",
        traceId: expect.any(String),
      })
    );
  });

  it("skips cancelled jobs", async () => {
    vi.useRealTimers();

    const api = createMockApi();
    const job = createJob({ id: "cancelled-job-2" });

    // Heartbeat immediately returns the job as cancelled
    (api.runners.heartbeat as ReturnType<typeof vi.fn>).mockReturnValue(
      createMockHttpResponsePromise({ cancelledJobIds: ["cancelled-job-2"] })
    );

    register({
      func: () => "should not run",
      name: "test-agent",
      project: "default",
      params: [{ name: "message", type: "string" }],
      docstring: "",
    });

    // Delay the job delivery so heartbeat fires first
    let callCount = 0;
    (api.runners.nextJob as ReturnType<typeof vi.fn>).mockImplementation(
      async () => {
        callCount++;
        if (callCount === 1) {
          // Delay to let heartbeat fire first and register the cancellation
          await new Promise((resolve) => setTimeout(resolve, 200));
          return { data: job, rawResponse: {} };
        }
        return createMockHttpResponsePromise(null);
      }
    );

    const loop = new InProcessRunnerLoop(api, "runner-1", {
      heartbeatIntervalMs: 20,
    });
    loop.start();

    await new Promise((resolve) => setTimeout(resolve, 800));
    loop.shutdown();

    // reportJobResult should NOT have been called for the cancelled job
    const reportCalls = (
      api.runners.reportJobResult as ReturnType<typeof vi.fn>
    ).mock.calls;
    const calledForCancelled = reportCalls.some(
      (call: unknown[]) => call[0] === "cancelled-job-2"
    );
    expect(calledForCancelled).toBe(false);
  });

  it("casts string-encoded inputs to declared param types before calling the function", async () => {
    const api = createMockApi();
    const captured: Record<string, unknown> = {};

    register({
      func: (query: string, count: number, score: number, active: boolean) => {
        captured.query = query;
        captured.count = count;
        captured.score = score;
        captured.active = active;
        return "ok";
      },
      name: "typed-agent",
      project: "default",
      params: [
        { name: "query", type: "string" },
        { name: "count", type: "number" },
        { name: "score", type: "number" },
        { name: "active", type: "boolean" },
      ],
      docstring: "",
    });

    const job = createJob({
      agentName: "typed-agent",
      inputs: { query: "hello", count: "5", score: "3.14", active: "true" },
    });

    let callCount = 0;
    (api.runners.nextJob as ReturnType<typeof vi.fn>).mockImplementation(() => {
      callCount++;
      if (callCount === 1) return createMockHttpResponsePromise(job);
      return createMockHttpResponsePromise(null);
    });

    const loop = new InProcessRunnerLoop(api, "runner-1");
    loop.start();
    await vi.advanceTimersByTimeAsync(0);
    loop.shutdown();

    expect(api.runners.reportJobResult).toHaveBeenCalledWith(
      "job-1",
      expect.objectContaining({ status: "completed" })
    );
    expect(captured).toMatchObject({
      query: "hello",
      count: 5,
      score: 3.14,
      active: true,
    });
    expect(typeof captured.count).toBe("number");
    expect(typeof captured.score).toBe("number");
    expect(typeof captured.active).toBe("boolean");
  });

  it("handles dict result without wrapping", async () => {
    vi.useRealTimers();

    const api = createMockApi();
    const job = createJob({ agentName: "dict-agent" });

    register({
      func: () => ({ answer: "42", score: 1.0 }),
      name: "dict-agent",
      project: "default",
      params: [{ name: "message", type: "string" }],
      docstring: "",
    });

    let callCount = 0;
    (api.runners.nextJob as ReturnType<typeof vi.fn>).mockImplementation(
      () => {
        callCount++;
        if (callCount === 1) {
          return createMockHttpResponsePromise(job);
        }
        return createMockHttpResponsePromise(null);
      }
    );

    const loop = new InProcessRunnerLoop(api, "runner-1");
    loop.start();

    await new Promise((resolve) => setTimeout(resolve, 500));
    loop.shutdown();

    expect(api.runners.reportJobResult).toHaveBeenCalledWith(
      "job-1",
      expect.objectContaining({
        status: "completed",
        result: { answer: "42", score: 1.0 },
      })
    );
  });
});
