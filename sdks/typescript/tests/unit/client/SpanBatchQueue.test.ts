import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import type { MockInstance } from "vitest";
import { SpanBatchQueue } from "@/client/SpanBatchQueue";
import type { OpikApiClientTemp } from "@/client/OpikApiClientTemp";
import type { SavedSpan } from "@/tracer/Span";

const MB = 1024 * 1024;
const LIMIT_MB = 20;
const bigValue = (mb: number) => ({ payload: "x".repeat(mb * MB) });
const asMarker = (v: unknown) => v as { opik_truncated?: boolean };

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

  const makeQueue = (maxSpanPayloadSizeMb?: number) =>
    new SpanBatchQueue(
      api as unknown as OpikApiClientTemp,
      0,
      maxSpanPayloadSizeMb,
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
