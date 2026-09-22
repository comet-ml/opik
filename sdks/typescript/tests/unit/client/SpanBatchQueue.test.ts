import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import type { MockInstance } from "vitest";
import { SpanBatchQueue } from "@/client/SpanBatchQueue";
import type { OpikApiClientTemp } from "@/client/OpikApiClientTemp";
import type { SavedSpan } from "@/tracer/Span";

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

const makeSpan = (fields: Record<string, unknown>): SavedSpan =>
  ({
    id: "span-1",
    traceId: "trace-1",
    startTime: new Date(),
    ...fields,
  }) as unknown as SavedSpan;

describe("SpanBatchQueue truncation wiring", () => {
  let createSpans: MockInstance;
  let updateSpan: MockInstance;
  let api: {
    spans: { createSpans: MockInstance; updateSpan: MockInstance };
    requestOptions: unknown;
  };

  const makeQueue = (maxPayloadSizeMb?: number) =>
    new SpanBatchQueue(
      api as unknown as OpikApiClientTemp,
      0,
      maxPayloadSizeMb,
    );

  beforeEach(() => {
    createSpans = vi.fn().mockResolvedValue(undefined);
    updateSpan = vi.fn().mockResolvedValue(undefined);
    api = {
      spans: { createSpans, updateSpan },
      requestOptions: { headers: { "x-test": "1" } },
    };
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it("truncates an oversized field on the create path", async () => {
    const queue = makeQueue(LIMIT_MB);
    queue.create(makeSpan({ output: bigValue(24) }));
    await queue.flush();

    expect(createSpans).toHaveBeenCalledTimes(1);
    const [{ spans }] = createSpans.mock.calls[0] as [{ spans: SavedSpan[] }];
    expect(asMarker(spans[0].output).opik_truncated).toBe(true);
  });

  it("truncates an oversized body on the update path", async () => {
    const queue = makeQueue(LIMIT_MB);
    // Flush the create first so the update routes through updateEntity rather than
    // coalescing into the still-pending create (which would exercise the create path).
    queue.create(makeSpan({}));
    await queue.flush();

    queue.update("span-1", {
      output: bigValue(24),
      traceId: "trace-1",
    } as unknown as Partial<SavedSpan>);
    await queue.flush();

    expect(updateSpan).toHaveBeenCalledTimes(1);
    const [, { body }] = updateSpan.mock.calls[0] as [
      string,
      { body: { output?: unknown } },
    ];
    expect(asMarker(body.output).opik_truncated).toBe(true);
  });

  it("passes the payload through unchanged when the cap is disabled (0)", async () => {
    const queue = makeQueue(0);
    const output = bigValue(24);
    queue.create(makeSpan({ output }));
    await queue.flush();

    const [{ spans }] = createSpans.mock.calls[0] as [{ spans: SavedSpan[] }];
    expect(asMarker(spans[0].output).opik_truncated).toBeUndefined();
    expect(spans[0].output).toBe(output);
  });

  it("defaults to the documented 20 MB cap when no limit is passed to the queue", async () => {
    const queue = makeQueue(); // third arg omitted -> DEFAULT_CONFIG (20), not disabled
    queue.create(makeSpan({ output: bigValue(24) }));
    await queue.flush();

    const [{ spans }] = createSpans.mock.calls[0] as [{ spans: SavedSpan[] }];
    expect(asMarker(spans[0].output).opik_truncated).toBe(true);
  });
});

describe("SpanBatchQueue attachment extraction wiring", () => {
  let createSpans: MockInstance;
  let startMultiPartUpload: MockInstance;
  let fetchSpy: MockInstance;
  let api: {
    spans: { createSpans: MockInstance; updateSpan: MockInstance };
    attachments: {
      startMultiPartUpload: MockInstance;
      completeMultiPartUpload: MockInstance;
    };
    requestOptions: unknown;
  };

  const makeQueue = (minSizeBytes: number) =>
    new SpanBatchQueue(api as unknown as OpikApiClientTemp, 0, LIMIT_MB, {
      minSizeBytes,
      apiUrl: "http://localhost:8080",
      workspaceName: "default",
    });

  beforeEach(() => {
    createSpans = vi.fn().mockResolvedValue(undefined);
    startMultiPartUpload = vi.fn().mockResolvedValue({
      uploadId: "BEMinIO",
      preSignUrls: ["http://localhost:8080/upload"],
    });
    api = {
      spans: { createSpans, updateSpan: vi.fn().mockResolvedValue(undefined) },
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

  it("strips an inline base64 image from the sent span and uploads it as an attachment", async () => {
    const queue = makeQueue(1000); // ~1.3k base64 chars is over this threshold
    const dataUri = `data:image/png;base64,${pngBase64(1000)}`;
    queue.create(makeSpan({ input: { image: dataUri } }));
    await queue.flush();

    // The base64 was stripped from the outbound payload and replaced by a placeholder...
    const [{ spans }] = createSpans.mock.calls[0] as [
      { spans: { input: { image: string } }[] },
    ];
    expect(spans[0].input.image).toMatch(
      /^\[input-attachment-\d+-\d+-sdk\.png\]$/,
    );
    expect(JSON.stringify(spans[0])).not.toContain("data:image");
    // ...and the image was uploaded as an attachment.
    expect(startMultiPartUpload).toHaveBeenCalledTimes(1);
    expect(fetchSpy).toHaveBeenCalledTimes(1);
  });

  it("leaves a below-threshold base64 image inline and uploads nothing", async () => {
    const queue = makeQueue(1_000_000); // 1 MB threshold -> the small image stays inline
    const dataUri = `data:image/png;base64,${pngBase64(1000)}`;
    queue.create(makeSpan({ input: { image: dataUri } }));
    await queue.flush();

    const [{ spans }] = createSpans.mock.calls[0] as [
      { spans: { input: { image: string } }[] },
    ];
    expect(spans[0].input.image).toBe(dataUri); // unchanged
    expect(startMultiPartUpload).not.toHaveBeenCalled();
    expect(fetchSpy).not.toHaveBeenCalled();
  });
});
