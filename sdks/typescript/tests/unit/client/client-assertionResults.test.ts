import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import type { MockInstance } from "vitest";
import { Opik } from "opik";
import { AssertionResultsBatchQueue } from "@/client/AssertionResultsBatchQueue";

describe("OpikClient.traceAssertionResultsBatchQueue wiring", () => {
  let client: Opik;

  beforeEach(() => {
    client = new Opik({ projectName: "test-project" });
  });

  it("should expose an AssertionResultsBatchQueue instance for traces", () => {
    expect(client.traceAssertionResultsBatchQueue).toBeInstanceOf(
      AssertionResultsBatchQueue
    );
  });

  it("should drain the assertion results queue on client.flush()", async () => {
    const flushSpy = vi.spyOn(
      client.traceAssertionResultsBatchQueue,
      "flush"
    );

    await client.flush({ silent: true });

    expect(flushSpy).toHaveBeenCalledTimes(1);
  });
});

describe("AssertionResultsBatchQueue.createEntities", () => {
  let storeAssertionsBatch: MockInstance;
  let queue: AssertionResultsBatchQueue;
  let api: { assertionResults: { storeAssertionsBatch: MockInstance }; requestOptions: unknown };

  beforeEach(() => {
    storeAssertionsBatch = vi.fn().mockResolvedValue(undefined);
    api = {
      assertionResults: { storeAssertionsBatch },
      requestOptions: { headers: { "x-test": "1" } },
    };
    queue = new AssertionResultsBatchQueue(
      api as unknown as ConstructorParameters<typeof AssertionResultsBatchQueue>[0],
      0,
      "TRACE"
    );
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it("should call storeAssertionsBatch with the configured entityType and forwarded items", async () => {
    queue.create({
      entityId: "trace-1",
      projectName: "test-project",
      name: "Response is helpful",
      status: "passed",
      reason: "Looks good",
      source: "sdk",
    });
    queue.create({
      entityId: "trace-1",
      projectName: "test-project",
      name: "No hallucinations",
      status: "failed",
      reason: "Hallucinated date",
      source: "sdk",
    });

    await queue.flush();

    expect(storeAssertionsBatch).toHaveBeenCalledTimes(1);
    expect(storeAssertionsBatch).toHaveBeenCalledWith(
      {
        entityType: "TRACE",
        assertionResults: [
          expect.objectContaining({
            entityId: "trace-1",
            name: "Response is helpful",
            status: "passed",
            source: "sdk",
          }),
          expect.objectContaining({
            entityId: "trace-1",
            name: "No hallucinations",
            status: "failed",
            source: "sdk",
          }),
        ],
      },
      api.requestOptions
    );
  });

  it("should not call the API when nothing has been queued", async () => {
    await queue.flush();
    expect(storeAssertionsBatch).not.toHaveBeenCalled();
  });
});
