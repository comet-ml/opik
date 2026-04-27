import { describe, it, expect, vi } from "vitest";
import {
  OpikDistributedTraceAttributes,
  attachToParent,
  extractOpikDistributedTraceAttributes,
} from "@/otel";
import type { OpenTelemetrySpanLike } from "@/otel";

describe("OpikDistributedTraceAttributes", () => {
  it("returns both attributes when trace_id and parent_span_id are provided", () => {
    const attrs = new OpikDistributedTraceAttributes("trace-123", "span-456");

    expect(attrs.asAttributes()).toEqual({
      "opik.trace_id": "trace-123",
      "opik.parent_span_id": "span-456",
    });
  });

  it("returns only trace_id when parent_span_id is undefined", () => {
    const attrs = new OpikDistributedTraceAttributes("trace-123");

    const result = attrs.asAttributes();

    expect(result).toEqual({ "opik.trace_id": "trace-123" });
    expect(result).not.toHaveProperty("opik.parent_span_id");
  });

  it("returns only trace_id when parent_span_id is explicitly undefined", () => {
    const attrs = new OpikDistributedTraceAttributes("trace-123", undefined);

    expect(attrs.asAttributes()).toEqual({ "opik.trace_id": "trace-123" });
  });
});

describe("extractOpikDistributedTraceAttributes", () => {
  it("returns attributes when both trace_id and parent_span_id headers are present", () => {
    const headers = {
      opik_trace_id: "trace-abc",
      opik_parent_span_id: "span-def",
    };

    const result = extractOpikDistributedTraceAttributes(headers);

    expect(result).not.toBeNull();
    expect(result!.asAttributes()).toEqual({
      "opik.trace_id": "trace-abc",
      "opik.parent_span_id": "span-def",
    });
  });

  it("returns attributes without parent_span_id when only trace_id header is present", () => {
    const headers = { opik_trace_id: "trace-abc" };

    const result = extractOpikDistributedTraceAttributes(headers);

    expect(result).not.toBeNull();
    expect(result!.asAttributes()).toEqual({ "opik.trace_id": "trace-abc" });
  });

  it("returns null when trace_id header is missing", () => {
    const headers = {
      opik_parent_span_id: "span-def",
      other_header: "value",
    };

    expect(extractOpikDistributedTraceAttributes(headers)).toBeNull();
  });

  it("returns null for empty headers", () => {
    expect(extractOpikDistributedTraceAttributes({})).toBeNull();
  });

  it("ignores headers other than opik distributed trace headers", () => {
    const headers = {
      opik_trace_id: "trace-abc",
      opik_parent_span_id: "span-def",
      authorization: "Bearer token",
      "content-type": "application/json",
    };

    const result = extractOpikDistributedTraceAttributes(headers);

    expect(result!.asAttributes()).toEqual({
      "opik.trace_id": "trace-abc",
      "opik.parent_span_id": "span-def",
    });
  });

  it("matches header names case-insensitively", () => {
    const headers = {
      OPIK_TRACE_ID: "trace-abc",
      "Opik_Parent_Span_Id": "span-def",
    };

    const result = extractOpikDistributedTraceAttributes(headers);

    expect(result!.asAttributes()).toEqual({
      "opik.trace_id": "trace-abc",
      "opik.parent_span_id": "span-def",
    });
  });

  it("uses the first value when an array-valued header is provided (node:http style)", () => {
    const headers = {
      opik_trace_id: ["trace-abc", "trace-other"],
    };

    const result = extractOpikDistributedTraceAttributes(headers);

    expect(result!.asAttributes()).toEqual({ "opik.trace_id": "trace-abc" });
  });

  it("supports a WHATWG Headers-like iterable", () => {
    const headers = new Headers({
      opik_trace_id: "trace-abc",
      opik_parent_span_id: "span-def",
    });

    const result = extractOpikDistributedTraceAttributes(headers);

    expect(result!.asAttributes()).toEqual({
      "opik.trace_id": "trace-abc",
      "opik.parent_span_id": "span-def",
    });
  });

  it("returns null when array-valued trace_id header is empty", () => {
    const headers = { opik_trace_id: [] as string[] };

    expect(extractOpikDistributedTraceAttributes(headers)).toBeNull();
  });
});

describe("attachToParent", () => {
  const makeSpan = (): OpenTelemetrySpanLike & {
    setAttributes: ReturnType<typeof vi.fn>;
  } => ({
    setAttributes: vi.fn(),
  });

  it("sets attributes and returns true when valid headers are provided", () => {
    const span = makeSpan();
    const headers = {
      opik_trace_id: "trace-abc",
      opik_parent_span_id: "span-def",
    };

    const result = attachToParent(span, headers);

    expect(result).toBe(true);
    expect(span.setAttributes).toHaveBeenCalledTimes(1);
    expect(span.setAttributes).toHaveBeenCalledWith({
      "opik.trace_id": "trace-abc",
      "opik.parent_span_id": "span-def",
    });
  });

  it("sets only trace_id and returns true when only trace_id header is present", () => {
    const span = makeSpan();
    const headers = { opik_trace_id: "trace-abc" };

    const result = attachToParent(span, headers);

    expect(result).toBe(true);
    expect(span.setAttributes).toHaveBeenCalledTimes(1);
    expect(span.setAttributes).toHaveBeenCalledWith({
      "opik.trace_id": "trace-abc",
    });
  });

  it("returns false and does not set attributes when trace_id is missing", () => {
    const span = makeSpan();
    const headers = { opik_parent_span_id: "span-def" };

    const result = attachToParent(span, headers);

    expect(result).toBe(false);
    expect(span.setAttributes).not.toHaveBeenCalled();
  });

  it("returns false and does not set attributes for empty headers", () => {
    const span = makeSpan();

    const result = attachToParent(span, {});

    expect(result).toBe(false);
    expect(span.setAttributes).not.toHaveBeenCalled();
  });
});