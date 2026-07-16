import { describe, it, expect, vi, afterEach } from "vitest";
import { logger } from "@/utils/logger";
import {
  truncateSpanFields,
  truncateSpanIfNeeded,
} from "@/client/spanTruncation";

const MB = 1024 * 1024;
const LIMIT_MB = 20;

const bigValue = (mb: number) => ({ payload: "x".repeat(mb * MB) });

// The marker replaces a field's value; read it back through a permissive shape.
const asMarker = (value: unknown) =>
  value as { opik_truncated?: boolean; reason?: string };

describe("truncateSpanFields", () => {
  it("leaves a within-limit span unchanged (same reference)", () => {
    const span = {
      id: "s1",
      input: { prompt: "small" },
      output: { result: "ok" },
    };

    const { result, truncated } = truncateSpanFields(span, LIMIT_MB);

    expect(truncated).toEqual([]);
    expect(result).toBe(span);
  });

  it("truncates an oversized field and keeps the small sibling", () => {
    const span = { id: "s1", input: { prompt: "small" }, output: bigValue(21) };

    const { result, truncated } = truncateSpanFields(span, LIMIT_MB);

    expect(truncated).toEqual(["output"]);
    expect(asMarker(result.output).opik_truncated).toBe(true);
    expect(asMarker(result.output).reason).toMatch(
      /^<omitted_due_to_size_\d+MB_error_code_413_400>$/,
    );
    expect(result.input).toEqual({ prompt: "small" }); // small sibling kept
  });

  it("truncates every field that individually exceeds the limit", () => {
    const span = { id: "s1", input: bigValue(25), output: bigValue(25) };

    const { truncated } = truncateSpanFields(span, LIMIT_MB);

    expect([...truncated].sort()).toEqual(["input", "output"]);
  });

  it("hard per-span cap: total over but no single field over -> truncates all", () => {
    const span = { id: "s1", input: bigValue(15), output: bigValue(15) };

    const { result, truncated } = truncateSpanFields(span, LIMIT_MB);

    expect([...truncated].sort()).toEqual(["input", "output"]);
    expect(asMarker(result.input).opik_truncated).toBe(true);
    expect(asMarker(result.output).opik_truncated).toBe(true);
  });

  it("never truncates metadata, and a huge metadata does not trigger others", () => {
    // metadata is exempt: a huge metadata must not be truncated, nor drag the
    // span over the cap and cause small input/output to be cut.
    const metadata = bigValue(25);
    const span = {
      id: "s1",
      input: { prompt: "small" },
      output: { result: "small" },
      metadata,
    };

    const { result, truncated } = truncateSpanFields(span, LIMIT_MB);

    expect(truncated).toEqual([]); // nothing truncated
    expect(result).toBe(span);
    expect(result.metadata).toBe(metadata); // metadata left intact
  });

  it("truncates an oversized input but keeps metadata", () => {
    const metadata = bigValue(25);
    const span = { id: "s1", input: bigValue(25), metadata };

    const { result, truncated } = truncateSpanFields(span, LIMIT_MB);

    expect(truncated).toEqual(["input"]);
    expect(asMarker(result.input).opik_truncated).toBe(true);
    expect(result.metadata).toBe(metadata); // metadata untouched
  });

  it("does not mutate the original span", () => {
    const bigOutput = bigValue(21);
    const span = { id: "s1", output: bigOutput };

    truncateSpanFields(span, LIMIT_MB);

    expect(span.output).toBe(bigOutput); // original untouched (non-mutating)
  });

  it("is fail-safe: a non-serializable field does not throw and is left untouched", () => {
    const circular: Record<string, unknown> = {};
    circular.self = circular; // JSON.stringify would throw on this
    const span = { id: "s1", output: circular };

    expect(() => truncateSpanFields(span, LIMIT_MB)).not.toThrow();

    const { result, truncated } = truncateSpanFields(span, LIMIT_MB);
    expect(truncated).toEqual([]); // can't measure -> not truncated
    expect(result).toBe(span);
  });

  it("treats a field too large to serialize (RangeError) as oversized and truncates it", () => {
    // Reproduce V8's "Invalid string length" deterministically without allocating >512 MiB:
    // a value whose serialization throws RangeError is the multi-GB payload this guard must
    // still truncate (not silently skip as if it were size 0).
    const tooLarge = {
      toJSON() {
        throw new RangeError("Invalid string length");
      },
    };
    const span = { id: "s1", output: tooLarge };

    const { result, truncated } = truncateSpanFields(span, LIMIT_MB);

    expect(truncated).toEqual(["output"]);
    expect(asMarker(result.output).opik_truncated).toBe(true);
  });

  it("returns the same span when there are no truncatable fields (metadata only)", () => {
    const span = { id: "s1", metadata: { note: "kept" } };

    const { result, truncated } = truncateSpanFields(span, LIMIT_MB);

    expect(truncated).toEqual([]);
    expect(result).toBe(span);
  });
});

describe("truncateSpanIfNeeded", () => {
  it("is a no-op when the limit is disabled (<= 0)", () => {
    const span = { id: "s1", output: bigValue(21) };

    const result = truncateSpanIfNeeded(span, 0, "s1");

    expect(result).toBe(span);
  });

  it("truncates when a field is over the limit", () => {
    const span = { id: "s1", output: bigValue(21) };

    const result = truncateSpanIfNeeded(span, LIMIT_MB, "s1");

    expect(asMarker(result.output).opik_truncated).toBe(true);
  });

  it("is a no-op when the limit is negative", () => {
    const span = { id: "s1", output: bigValue(21) };

    const result = truncateSpanIfNeeded(span, -1, "s1");

    expect(result).toBe(span);
  });
});

describe("truncateSpanIfNeeded logging", () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it("warns once with the span id and truncated field(s) on truncation", () => {
    const warn = vi.spyOn(logger, "warn").mockImplementation(() => undefined);

    truncateSpanIfNeeded(
      { id: "s1", output: bigValue(21) },
      LIMIT_MB,
      "span-42",
    );

    expect(warn).toHaveBeenCalledTimes(1);
    expect(warn.mock.calls[0][0]).toContain("span-42");
    expect(warn.mock.calls[0][0]).toContain("output");
  });

  it("falls back to 'unknown' when no span id is given", () => {
    const warn = vi.spyOn(logger, "warn").mockImplementation(() => undefined);

    truncateSpanIfNeeded({ output: bigValue(21) }, LIMIT_MB);

    expect(warn).toHaveBeenCalledTimes(1);
    expect(warn.mock.calls[0][0]).toContain("unknown");
  });

  it("does not warn on the under-limit or disabled paths", () => {
    const warn = vi.spyOn(logger, "warn").mockImplementation(() => undefined);

    truncateSpanIfNeeded({ id: "s1", output: { ok: true } }, LIMIT_MB, "s1");
    truncateSpanIfNeeded({ id: "s1", output: bigValue(21) }, 0, "s1");

    expect(warn).not.toHaveBeenCalled();
  });
});
