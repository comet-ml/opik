import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import type { MockInstance } from "vitest";
import { Opik } from "opik";
import { AssertionResultsBatchQueue } from "@/client/AssertionResultsBatchQueue";
import type { OpikApiClientTemp } from "@/client/OpikApiClientTemp";
import { OpikApiError } from "@/rest_api/errors/OpikApiError";
import { logger } from "@/utils/logger";

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
  let scoreBatchOfTraces: MockInstance;
  let queue: AssertionResultsBatchQueue;
  let api: {
    assertionResults: { storeAssertionsBatch: MockInstance };
    traces: { scoreBatchOfTraces: MockInstance };
    requestOptions: unknown;
  };

  beforeEach(() => {
    storeAssertionsBatch = vi.fn().mockResolvedValue(undefined);
    scoreBatchOfTraces = vi.fn().mockResolvedValue(undefined);
    api = {
      assertionResults: { storeAssertionsBatch },
      traces: { scoreBatchOfTraces },
      requestOptions: { headers: { "x-test": "1" } },
    };
    queue = new AssertionResultsBatchQueue(
      api as unknown as OpikApiClientTemp,
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

  describe("legacy fallback on 404", () => {
    const make404 = () =>
      new OpikApiError({
        statusCode: 404,
        body: "Not Found",
        rawResponse: new Response(null, { status: 404 }),
      });

    it("should fall back to feedback-scores with categoryName=\"suite_assertion\" when the new endpoint 404s", async () => {
      const warnSpy = vi
        .spyOn(logger, "warn")
        .mockImplementation(() => undefined);
      storeAssertionsBatch.mockRejectedValueOnce(make404());

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
      expect(scoreBatchOfTraces).toHaveBeenCalledTimes(1);
      expect(scoreBatchOfTraces).toHaveBeenCalledWith(
        {
          scores: [
            {
              id: "trace-1",
              name: "Response is helpful",
              value: 1,
              categoryName: "suite_assertion",
              reason: "Looks good",
              source: "sdk",
              projectName: "test-project",
              projectId: undefined,
            },
            {
              id: "trace-1",
              name: "No hallucinations",
              value: 0,
              categoryName: "suite_assertion",
              reason: "Hallucinated date",
              source: "sdk",
              projectName: "test-project",
              projectId: undefined,
            },
          ],
        },
        api.requestOptions
      );
      expect(warnSpy).toHaveBeenCalledTimes(1);
      expect(warnSpy.mock.calls[0][0]).toMatch(/OPIK-6048/);

      warnSpy.mockRestore();
    });

    it("should latch the fallback so subsequent batches skip the new endpoint", async () => {
      vi.spyOn(logger, "warn").mockImplementation(() => undefined);
      storeAssertionsBatch.mockRejectedValueOnce(make404());

      queue.create({
        entityId: "trace-1",
        projectName: "test-project",
        name: "First",
        status: "passed",
        source: "sdk",
      });
      await queue.flush();

      queue.create({
        entityId: "trace-2",
        projectName: "test-project",
        name: "Second",
        status: "failed",
        source: "sdk",
      });
      await queue.flush();

      // New endpoint tried only on the first batch; second batch goes straight to legacy.
      expect(storeAssertionsBatch).toHaveBeenCalledTimes(1);
      expect(scoreBatchOfTraces).toHaveBeenCalledTimes(2);
    });

    it("should propagate non-404 errors instead of falling back", async () => {
      storeAssertionsBatch.mockRejectedValueOnce(
        new OpikApiError({
          statusCode: 500,
          body: "boom",
          rawResponse: new Response(null, { status: 500 }),
        })
      );

      queue.create({
        entityId: "trace-1",
        projectName: "test-project",
        name: "First",
        status: "passed",
        source: "sdk",
      });

      await expect(
        // Drive createEntities directly so we observe the throw — BatchQueue.flush()
        // swallows errors via its own try/catch logger.
        (queue as unknown as {
          createEntities: (
            items: { entityId: string; name: string; status: string; source: string; projectName: string }[]
          ) => Promise<void>;
        }).createEntities([
          {
            entityId: "trace-1",
            name: "First",
            status: "passed",
            source: "sdk",
            projectName: "test-project",
          },
        ])
      ).rejects.toMatchObject({ statusCode: 500 });

      expect(scoreBatchOfTraces).not.toHaveBeenCalled();
    });

    it("should propagate 404 for non-TRACE entity types instead of falling back", async () => {
      const spanQueue = new AssertionResultsBatchQueue(
        api as unknown as OpikApiClientTemp,
        0,
        "SPAN"
      );
      storeAssertionsBatch.mockRejectedValueOnce(make404());

      await expect(
        (spanQueue as unknown as {
          createEntities: (
            items: { entityId: string; name: string; status: string; source: string; projectName: string }[]
          ) => Promise<void>;
        }).createEntities([
          {
            entityId: "span-1",
            name: "First",
            status: "passed",
            source: "sdk",
            projectName: "test-project",
          },
        ])
      ).rejects.toMatchObject({ statusCode: 404 });

      expect(scoreBatchOfTraces).not.toHaveBeenCalled();
    });

    it("should forward AssertionResultBatchItem.source through the legacy fallback mapping", async () => {
      vi.spyOn(logger, "warn").mockImplementation(() => undefined);
      storeAssertionsBatch.mockRejectedValueOnce(make404());

      queue.create({
        entityId: "trace-1",
        projectName: "test-project",
        name: "From the UI",
        status: "passed",
        source: "ui",
      });

      await queue.flush();

      expect(scoreBatchOfTraces).toHaveBeenCalledTimes(1);
      const [{ scores }] = scoreBatchOfTraces.mock.calls[0] as [
        { scores: Array<{ source: string }> },
      ];
      expect(scores[0].source).toBe("ui");
    });
  });
});
