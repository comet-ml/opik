import { describe, it, expect, vi, afterEach } from "vitest";
import { logger } from "@/utils/logger";
import {
  truncatePayloadFields,
  truncatePayloadIfNeeded,
} from "@/client/payloadTruncation";

const MB = 1024 * 1024;
const LIMIT_MB = 20;

const bigValue = (mb: number) => ({ payload: "x".repeat(mb * MB) });

// The marker replaces a field's value; read it back through a permissive shape.
const asMarker = (value: unknown) =>
  value as { opik_truncated?: boolean; reason?: string };

describe("truncatePayloadFields", () => {
  it("leaves a within-limit payload unchanged (same reference)", () => {
    const span = {
      id: "s1",
      input: { prompt: "small" },
      output: { result: "ok" },
    };

    const { result, truncated } = truncatePayloadFields(span, LIMIT_MB);

    expect(truncated).toEqual([]);
    expect(result).toBe(span);
  });

  it("truncates an oversized field and keeps the small sibling", () => {
    const span = { id: "s1", input: { prompt: "small" }, output: bigValue(21) };

    const { result, truncated } = truncatePayloadFields(span, LIMIT_MB);

    expect(truncated).toEqual(["output"]);
    expect(asMarker(result.output).opik_truncated).toBe(true);
    expect(asMarker(result.output).reason).toMatch(
      /^<omitted_due_to_size_\d+MB_error_code_413_400>$/,
    );
    expect(result.input).toEqual({ prompt: "small" }); // small sibling kept
  });

  it("truncates every field that individually exceeds the limit", () => {
    const span = { id: "s1", input: bigValue(25), output: bigValue(25) };

    const { truncated } = truncatePayloadFields(span, LIMIT_MB);

    expect([...truncated].sort()).toEqual(["input", "output"]);
  });

  it("hard per-object cap: total over but no single field over -> truncates all", () => {
    const span = { id: "s1", input: bigValue(15), output: bigValue(15) };

    const { result, truncated } = truncatePayloadFields(span, LIMIT_MB);

    expect([...truncated].sort()).toEqual(["input", "output"]);
    expect(asMarker(result.input).opik_truncated).toBe(true);
    expect(asMarker(result.output).opik_truncated).toBe(true);
  });

  it("applies the same rule to a trace-shaped payload (output only)", () => {
    // @track mirrors the outermost call's output onto the trace, so a trace can
    // carry an oversized field just like a span. Same helper, same result.
    const trace = { id: "t1", output: bigValue(21) };

    const { result, truncated } = truncatePayloadFields(trace, LIMIT_MB);

    expect(truncated).toEqual(["output"]);
    expect(asMarker(result.output).opik_truncated).toBe(true);
  });

  it("never truncates metadata, and a huge metadata does not trigger others", () => {
    // metadata is exempt: a huge metadata must not be truncated, nor drag the
    // object over the cap and cause small input/output to be cut.
    const metadata = bigValue(25);
    const span = {
      id: "s1",
      input: { prompt: "small" },
      output: { result: "small" },
      metadata,
    };

    const { result, truncated } = truncatePayloadFields(span, LIMIT_MB);

    expect(truncated).toEqual([]); // nothing truncated
    expect(result).toBe(span);
    expect(result.metadata).toBe(metadata); // metadata left intact
  });

  it("truncates an oversized input but keeps metadata", () => {
    const metadata = bigValue(25);
    const span = { id: "s1", input: bigValue(25), metadata };

    const { result, truncated } = truncatePayloadFields(span, LIMIT_MB);

    expect(truncated).toEqual(["input"]);
    expect(asMarker(result.input).opik_truncated).toBe(true);
    expect(result.metadata).toBe(metadata); // metadata untouched
  });

  it("does not mutate the original payload", () => {
    const bigOutput = bigValue(21);
    const span = { id: "s1", output: bigOutput };

    truncatePayloadFields(span, LIMIT_MB);

    expect(span.output).toBe(bigOutput); // original untouched (non-mutating)
  });

  it("force-truncates a non-serializable (circular) field so the send can't throw on it", () => {
    const circular: Record<string, unknown> = {};
    circular.self = circular; // JSON.stringify would throw on this
    const span = { id: "s1", output: circular, input: { ok: true } };

    expect(() => truncatePayloadFields(span, LIMIT_MB)).not.toThrow();

    const { result, truncated } = truncatePayloadFields(span, LIMIT_MB);
    // Unserializable here == unsendable on the wire, so it's replaced with a marker (fail-safe)
    // rather than left in place to crash create/update later.
    expect(truncated).toEqual(["output"]);
    expect(asMarker(result.output).opik_truncated).toBe(true);
    expect(result.input).toEqual({ ok: true }); // serializable sibling kept
  });

  it("force-truncates an unserializable field even when the cap exceeds 512 MB", () => {
    // Regression: a finite sentinel (the old 512) let a larger configured cap slip an
    // unsendable payload through. Infinity must beat ANY finite cap.
    const circular: Record<string, unknown> = {};
    circular.self = circular;
    const span = { id: "s1", output: circular };

    const { result, truncated } = truncatePayloadFields(span, 1000);

    expect(truncated).toEqual(["output"]);
    expect(asMarker(result.output).opik_truncated).toBe(true);
    expect(asMarker(result.output).reason).toBe(
      "<omitted_unserializable_error_code_413_400>",
    );
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

    const { result, truncated } = truncatePayloadFields(span, LIMIT_MB);

    expect(truncated).toEqual(["output"]);
    expect(asMarker(result.output).opik_truncated).toBe(true);
  });

  it("returns the same payload when there are no truncatable fields (metadata only)", () => {
    const span = { id: "s1", metadata: { note: "kept" } };

    const { result, truncated } = truncatePayloadFields(span, LIMIT_MB);

    expect(truncated).toEqual([]);
    expect(result).toBe(span);
  });
});

describe("truncatePayloadIfNeeded", () => {
  it("is a no-op when the limit is disabled (<= 0)", () => {
    const span = { id: "s1", output: bigValue(21) };

    const result = truncatePayloadIfNeeded(span, 0, "span", "s1");

    expect(result).toBe(span);
  });

  it("truncates when a field is over the limit", () => {
    const span = { id: "s1", output: bigValue(21) };

    const result = truncatePayloadIfNeeded(span, LIMIT_MB, "span", "s1");

    expect(asMarker(result.output).opik_truncated).toBe(true);
  });

  it("is a no-op when the limit is negative", () => {
    const span = { id: "s1", output: bigValue(21) };

    const result = truncatePayloadIfNeeded(span, -1, "span", "s1");

    expect(result).toBe(span);
  });

  it("defaults to the 'span' kind when none is given", () => {
    const span = { id: "s1", output: bigValue(21) };

    const result = truncatePayloadIfNeeded(span, LIMIT_MB);

    expect(asMarker(result.output).opik_truncated).toBe(true);
  });
});

describe("truncatePayloadIfNeeded logging", () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it("warns once with the span id and truncated field(s) on truncation", () => {
    const warn = vi.spyOn(logger, "warn").mockImplementation(() => undefined);

    truncatePayloadIfNeeded(
      { id: "s1", output: bigValue(21) },
      LIMIT_MB,
      "span",
      "span-42",
    );

    expect(warn).toHaveBeenCalledTimes(1);
    expect(warn.mock.calls[0][0]).toContain("Span");
    expect(warn.mock.calls[0][0]).toContain("span-42");
    expect(warn.mock.calls[0][0]).toContain("output");
  });

  it("uses the 'Trace' label and the trace id for a trace payload", () => {
    const warn = vi.spyOn(logger, "warn").mockImplementation(() => undefined);

    truncatePayloadIfNeeded(
      { id: "t1", output: bigValue(21) },
      LIMIT_MB,
      "trace",
      "trace-7",
    );

    expect(warn).toHaveBeenCalledTimes(1);
    expect(warn.mock.calls[0][0]).toContain("Trace");
    expect(warn.mock.calls[0][0]).toContain("trace-7");
  });

  it("falls back to 'unknown' when no id is given", () => {
    const warn = vi.spyOn(logger, "warn").mockImplementation(() => undefined);

    truncatePayloadIfNeeded({ output: bigValue(21) }, LIMIT_MB, "span");

    expect(warn).toHaveBeenCalledTimes(1);
    expect(warn.mock.calls[0][0]).toContain("unknown");
  });

  it("does not warn on the under-limit or disabled paths", () => {
    const warn = vi.spyOn(logger, "warn").mockImplementation(() => undefined);

    truncatePayloadIfNeeded({ id: "s1", output: { ok: true } }, LIMIT_MB, "span", "s1");
    truncatePayloadIfNeeded({ id: "s1", output: bigValue(21) }, 0, "span", "s1");

    expect(warn).not.toHaveBeenCalled();
  });
});
