import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import type { MockInstance } from "vitest";
import { TraceBatchQueue } from "@/client/TraceBatchQueue";
import type { OpikApiClientTemp } from "@/client/OpikApiClientTemp";
import type { SavedTrace } from "@/tracer/Trace";

const MB = 1024 * 1024;
const LIMIT_MB = 20;
const bigValue = (mb: number) => ({ payload: "x".repeat(mb * MB) });
const asMarker = (v: unknown) => v as { opik_truncated?: boolean };

const PNG = [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a];
const pngBase64 = (bytes: number) => {
  const buf = Buffer.alloc(bytes);
  PNG.forEach((b, i) => (buf[i] = b));
  return buf.toString("base64");
};

const makeTrace = (fields: Record<string, unknown>): SavedTrace =>
  ({
    id: "trace-1",
    startTime: new Date(),
    ...fields,
  }) as unknown as SavedTrace;

describe("TraceBatchQueue truncation wiring", () => {
  let createTraces: MockInstance;
  let updateTrace: MockInstance;
  let api: {
    traces: { createTraces: MockInstance; updateTrace: MockInstance };
    requestOptions: unknown;
  };

  const makeQueue = (maxPayloadSizeMb?: number) =>
    new TraceBatchQueue(api as unknown as OpikApiClientTemp, 0, maxPayloadSizeMb);

  beforeEach(() => {
    createTraces = vi.fn().mockResolvedValue(undefined);
    updateTrace = vi.fn().mockResolvedValue(undefined);
    api = {
      traces: { createTraces, updateTrace },
      requestOptions: { headers: { "x-test": "1" } },
    };
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it("truncates an oversized field on the create path", async () => {
    const queue = makeQueue(LIMIT_MB);
    queue.create(makeTrace({ output: bigValue(24) }));
    await queue.flush();

    expect(createTraces).toHaveBeenCalledTimes(1);
    const [{ traces }] = createTraces.mock.calls[0] as [{ traces: SavedTrace[] }];
    expect(asMarker(traces[0].output).opik_truncated).toBe(true);
  });

  it("truncates an oversized body on the update path", async () => {
    const queue = makeQueue(LIMIT_MB);
    // Flush the create first so the update routes through updateEntity rather than
    // coalescing into the still-pending create.
    queue.create(makeTrace({}));
    await queue.flush();

    queue.update("trace-1", {
      output: bigValue(24),
    } as unknown as Partial<SavedTrace>);
    await queue.flush();

    expect(updateTrace).toHaveBeenCalledTimes(1);
    const [, { body }] = updateTrace.mock.calls[0] as [
      string,
      { body: { output?: unknown } },
    ];
    expect(asMarker(body.output).opik_truncated).toBe(true);
  });

  it("passes the payload through unchanged when the cap is disabled (0)", async () => {
    const queue = makeQueue(0);
    const output = bigValue(24);
    queue.create(makeTrace({ output }));
    await queue.flush();

    const [{ traces }] = createTraces.mock.calls[0] as [{ traces: SavedTrace[] }];
    expect(asMarker(traces[0].output).opik_truncated).toBeUndefined();
    expect(traces[0].output).toBe(output);
  });

  it("defaults to the documented 20 MB cap when no limit is passed to the queue", async () => {
    const queue = makeQueue(); // third arg omitted -> DEFAULT_CONFIG (20), not disabled
    queue.create(makeTrace({ output: bigValue(24) }));
    await queue.flush();

    const [{ traces }] = createTraces.mock.calls[0] as [{ traces: SavedTrace[] }];
    expect(asMarker(traces[0].output).opik_truncated).toBe(true);
  });
});

describe("TraceBatchQueue attachment extraction wiring", () => {
  let createTraces: MockInstance;
  let startMultiPartUpload: MockInstance;
  let fetchSpy: MockInstance;
  let api: {
    traces: { createTraces: MockInstance; updateTrace: MockInstance };
    attachments: {
      startMultiPartUpload: MockInstance;
      completeMultiPartUpload: MockInstance;
    };
    requestOptions: unknown;
  };

  const makeQueue = (minSizeBytes: number) =>
    new TraceBatchQueue(api as unknown as OpikApiClientTemp, 0, LIMIT_MB, {
      minSizeBytes,
      apiUrl: "http://localhost:8080",
      workspaceName: "default",
    });

  beforeEach(() => {
    createTraces = vi.fn().mockResolvedValue(undefined);
    startMultiPartUpload = vi.fn().mockResolvedValue({
      uploadId: "BEMinIO",
      preSignUrls: ["http://localhost:8080/upload"],
    });
    api = {
      traces: {
        createTraces,
        updateTrace: vi.fn().mockResolvedValue(undefined),
      },
      attachments: {
        startMultiPartUpload,
        completeMultiPartUpload: vi.fn().mockResolvedValue(undefined),
      },
      requestOptions: {},
    };
    fetchSpy = vi
      .spyOn(globalThis, "fetch")
      .mockResolvedValue(new Response(null, { status: 204 }));
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it("strips an inline base64 image from the sent trace and uploads it as an attachment", async () => {
    const queue = makeQueue(1000);
    const dataUri = `data:image/png;base64,${pngBase64(1000)}`;
    queue.create(makeTrace({ output: { image: dataUri } }));
    await queue.flush();

    const [{ traces }] = createTraces.mock.calls[0] as [
      { traces: { output: { image: string } }[] },
    ];
    expect(traces[0].output.image).toMatch(
      /^\[output-attachment-\d+-\d+-sdk\.png\]$/,
    );
    expect(JSON.stringify(traces[0])).not.toContain("data:image");
    expect(startMultiPartUpload).toHaveBeenCalledTimes(1);
    expect(fetchSpy).toHaveBeenCalledTimes(1);
  });
});
