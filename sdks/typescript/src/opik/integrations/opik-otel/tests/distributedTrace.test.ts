import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { v4 as uuidv4 } from "uuid";
import { logger } from "opik";
import {
  OPIK_SPAN_ID,
  OpikDistributedTraceAttributes,
  attachToParent,
  extractOpikDistributedTraceAttributes,
} from "../src/index";
import type { OpenTelemetrySpanLike } from "../src/index";
import { isValidUuidV7 } from "../src/internal";

const TRACE_ID = "0193b3a5-1234-7abc-9def-0123456789ab";
const PARENT_SPAN_ID = "0193b3a5-5678-7abc-9def-0123456789cd";
const UUID_V4 = uuidv4();

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
  let warnSpy: ReturnType<typeof vi.spyOn>;

  beforeEach(() => {
    warnSpy = vi.spyOn(logger, "warn").mockImplementation(() => undefined);
  });

  afterEach(() => {
    warnSpy.mockRestore();
  });

  it("returns attributes when both trace_id and parent_span_id headers are present", () => {
    const headers = {
      opik_trace_id: TRACE_ID,
      opik_parent_span_id: PARENT_SPAN_ID,
    };

    const result = extractOpikDistributedTraceAttributes(headers);

    expect(result).not.toBeNull();
    expect(result!.asAttributes()).toEqual({
      "opik.trace_id": TRACE_ID,
      "opik.parent_span_id": PARENT_SPAN_ID,
    });
    expect(warnSpy).not.toHaveBeenCalled();
  });

  it("returns attributes without parent_span_id when only trace_id header is present", () => {
    const headers = { opik_trace_id: TRACE_ID };

    const result = extractOpikDistributedTraceAttributes(headers);

    expect(result).not.toBeNull();
    expect(result!.asAttributes()).toEqual({ "opik.trace_id": TRACE_ID });
  });

  it("returns null and warns when trace_id is missing but parent_span_id is provided", () => {
    const headers = {
      opik_parent_span_id: PARENT_SPAN_ID,
      other_header: "value",
    };

    expect(extractOpikDistributedTraceAttributes(headers)).toBeNull();
    expect(warnSpy).toHaveBeenCalledTimes(1);
    expect(warnSpy.mock.calls[0]?.[0]).toContain("opik_trace_id");
    expect(warnSpy.mock.calls[0]?.[0]).toContain("opik_parent_span_id");
  });

  it("returns null without warning when both trace_id and parent_span_id are missing", () => {
    const headers = { other_header: "value" };

    expect(extractOpikDistributedTraceAttributes(headers)).toBeNull();
    expect(warnSpy).not.toHaveBeenCalled();
  });

  it("returns null and warns when trace_id is blank but parent_span_id is a valid UUID", () => {
    const headers = {
      opik_trace_id: "   ",
      opik_parent_span_id: PARENT_SPAN_ID,
    };

    expect(extractOpikDistributedTraceAttributes(headers)).toBeNull();
    expect(warnSpy).toHaveBeenCalledTimes(1);
    expect(warnSpy.mock.calls[0]?.[0]).toContain("opik_trace_id");
  });

  it("returns null for empty headers", () => {
    expect(extractOpikDistributedTraceAttributes({})).toBeNull();
    expect(warnSpy).not.toHaveBeenCalled();
  });

  it("ignores headers other than opik distributed trace headers", () => {
    const headers = {
      opik_trace_id: TRACE_ID,
      opik_parent_span_id: PARENT_SPAN_ID,
      authorization: "Bearer token",
      "content-type": "application/json",
    };

    const result = extractOpikDistributedTraceAttributes(headers);

    expect(result!.asAttributes()).toEqual({
      "opik.trace_id": TRACE_ID,
      "opik.parent_span_id": PARENT_SPAN_ID,
    });
  });

  it("matches header names case-insensitively", () => {
    const headers = {
      OPIK_TRACE_ID: TRACE_ID,
      Opik_Parent_Span_Id: PARENT_SPAN_ID,
    };

    const result = extractOpikDistributedTraceAttributes(headers);

    expect(result!.asAttributes()).toEqual({
      "opik.trace_id": TRACE_ID,
      "opik.parent_span_id": PARENT_SPAN_ID,
    });
  });

  it("uses the first value when an array-valued header is provided (node:http style)", () => {
    const headers = {
      opik_trace_id: [TRACE_ID, "another-value"],
    };

    const result = extractOpikDistributedTraceAttributes(headers);

    expect(result!.asAttributes()).toEqual({ "opik.trace_id": TRACE_ID });
  });

  it("supports a WHATWG Headers-like iterable", () => {
    const headers = new Headers({
      opik_trace_id: TRACE_ID,
      opik_parent_span_id: PARENT_SPAN_ID,
    });

    const result = extractOpikDistributedTraceAttributes(headers);

    expect(result!.asAttributes()).toEqual({
      "opik.trace_id": TRACE_ID,
      "opik.parent_span_id": PARENT_SPAN_ID,
    });
  });

  it("returns null when array-valued trace_id header is empty", () => {
    const headers = { opik_trace_id: [] as string[] };

    expect(extractOpikDistributedTraceAttributes(headers)).toBeNull();
    expect(warnSpy).not.toHaveBeenCalled();
  });

  it("returns null when trace_id header is an empty string", () => {
    expect(
      extractOpikDistributedTraceAttributes({ opik_trace_id: "" })
    ).toBeNull();
    expect(warnSpy).not.toHaveBeenCalled();
  });

  it("returns null when trace_id header is whitespace-only", () => {
    expect(
      extractOpikDistributedTraceAttributes({ opik_trace_id: "   \t\n" })
    ).toBeNull();
    expect(warnSpy).not.toHaveBeenCalled();
  });

  it("trims surrounding whitespace from trace_id and parent_span_id", () => {
    const headers = {
      opik_trace_id: `  ${TRACE_ID}  `,
      opik_parent_span_id: `\t${PARENT_SPAN_ID}\n`,
    };

    const result = extractOpikDistributedTraceAttributes(headers);

    expect(result!.asAttributes()).toEqual({
      "opik.trace_id": TRACE_ID,
      "opik.parent_span_id": PARENT_SPAN_ID,
    });
  });

  it("treats whitespace-only parent_span_id as absent", () => {
    const headers = {
      opik_trace_id: TRACE_ID,
      opik_parent_span_id: "   ",
    };

    const result = extractOpikDistributedTraceAttributes(headers);

    expect(result!.asAttributes()).toEqual({ "opik.trace_id": TRACE_ID });
    expect(warnSpy).not.toHaveBeenCalled();
  });

  it("returns null and warns when trace_id is not a valid UUID", () => {
    const headers = { opik_trace_id: "not-a-uuid" };

    expect(extractOpikDistributedTraceAttributes(headers)).toBeNull();
    expect(warnSpy).toHaveBeenCalledTimes(1);
    expect(warnSpy.mock.calls[0]?.[0]).toContain("opik_trace_id");
  });

  it("returns null and warns when trace_id is a valid UUID but not v7", () => {
    const headers = { opik_trace_id: UUID_V4 };

    expect(extractOpikDistributedTraceAttributes(headers)).toBeNull();
    expect(warnSpy).toHaveBeenCalledTimes(1);
    expect(warnSpy.mock.calls[0]?.[0]).toContain("opik_trace_id");
  });

  it("drops parent_span_id with a warning when it is a non-v7 UUID", () => {
    const headers = {
      opik_trace_id: TRACE_ID,
      opik_parent_span_id: UUID_V4,
    };

    const result = extractOpikDistributedTraceAttributes(headers);

    expect(result).not.toBeNull();
    expect(result!.asAttributes()).toEqual({ "opik.trace_id": TRACE_ID });
    expect(warnSpy).toHaveBeenCalledTimes(1);
    expect(warnSpy.mock.calls[0]?.[0]).toContain("opik_parent_span_id");
  });

  it("drops parent_span_id with a warning when it is not a valid UUID but trace_id is", () => {
    const headers = {
      opik_trace_id: TRACE_ID,
      opik_parent_span_id: "not-a-uuid",
    };

    const result = extractOpikDistributedTraceAttributes(headers);

    expect(result).not.toBeNull();
    expect(result!.asAttributes()).toEqual({ "opik.trace_id": TRACE_ID });
    expect(warnSpy).toHaveBeenCalledTimes(1);
    expect(warnSpy.mock.calls[0]?.[0]).toContain("opik_parent_span_id");
  });

  it("warns once for trace_id and does not check parent_span_id when trace_id is invalid", () => {
    const headers = {
      opik_trace_id: "garbage",
      opik_parent_span_id: "also-garbage",
    };

    expect(extractOpikDistributedTraceAttributes(headers)).toBeNull();
    expect(warnSpy).toHaveBeenCalledTimes(1);
    expect(warnSpy.mock.calls[0]?.[0]).toContain("opik_trace_id");
  });
});

describe("attachToParent", () => {
  const makeSpan = (): OpenTelemetrySpanLike & {
    setAttributes: ReturnType<typeof vi.fn>;
  } => ({
    setAttributes: vi.fn(),
  });

  let warnSpy: ReturnType<typeof vi.spyOn>;

  beforeEach(() => {
    warnSpy = vi.spyOn(logger, "warn").mockImplementation(() => undefined);
  });

  afterEach(() => {
    warnSpy.mockRestore();
  });

  it("sets attributes and returns true when valid headers are provided", () => {
    const span = makeSpan();
    const headers = {
      opik_trace_id: TRACE_ID,
      opik_parent_span_id: PARENT_SPAN_ID,
    };

    const result = attachToParent(span, headers);

    expect(result).toBe(true);
    expect(span.setAttributes).toHaveBeenCalledTimes(1);
    const attrs = span.setAttributes.mock.calls[0]?.[0] as Record<
      string,
      string
    >;
    expect(attrs["opik.trace_id"]).toBe(TRACE_ID);
    expect(attrs["opik.parent_span_id"]).toBe(PARENT_SPAN_ID);
    // boundary span gets a freshly minted UUIDv7 to chain descendants through
    expect(isValidUuidV7(attrs[OPIK_SPAN_ID])).toBe(true);
  });

  it("sets only trace_id and returns true when only trace_id header is present", () => {
    const span = makeSpan();
    const headers = { opik_trace_id: TRACE_ID };

    const result = attachToParent(span, headers);

    expect(result).toBe(true);
    expect(span.setAttributes).toHaveBeenCalledTimes(1);
    const attrs = span.setAttributes.mock.calls[0]?.[0] as Record<
      string,
      string
    >;
    expect(attrs["opik.trace_id"]).toBe(TRACE_ID);
    expect(attrs["opik.parent_span_id"]).toBeUndefined();
    expect(isValidUuidV7(attrs[OPIK_SPAN_ID])).toBe(true);
  });

  it("returns false, warns, and does not set attributes when trace_id is missing but parent_span_id is provided", () => {
    const span = makeSpan();
    const headers = { opik_parent_span_id: PARENT_SPAN_ID };

    const result = attachToParent(span, headers);

    expect(result).toBe(false);
    expect(span.setAttributes).not.toHaveBeenCalled();
    expect(warnSpy).toHaveBeenCalledTimes(1);
  });

  it("returns false and does not set attributes for empty headers", () => {
    const span = makeSpan();

    const result = attachToParent(span, {});

    expect(result).toBe(false);
    expect(span.setAttributes).not.toHaveBeenCalled();
  });

  it("returns false and does not set attributes when trace_id is an empty string", () => {
    const span = makeSpan();

    const result = attachToParent(span, { opik_trace_id: "" });

    expect(result).toBe(false);
    expect(span.setAttributes).not.toHaveBeenCalled();
  });

  it("returns false and does not set attributes when trace_id is whitespace-only", () => {
    const span = makeSpan();

    const result = attachToParent(span, { opik_trace_id: "   " });

    expect(result).toBe(false);
    expect(span.setAttributes).not.toHaveBeenCalled();
  });

  it("returns false, warns, and does not set attributes when trace_id is not a UUID", () => {
    const span = makeSpan();

    const result = attachToParent(span, { opik_trace_id: "not-a-uuid" });

    expect(result).toBe(false);
    expect(span.setAttributes).not.toHaveBeenCalled();
    expect(warnSpy).toHaveBeenCalledTimes(1);
  });

  it("returns true and attaches only trace_id when parent_span_id is not a UUID", () => {
    const span = makeSpan();
    const headers = {
      opik_trace_id: TRACE_ID,
      opik_parent_span_id: "not-a-uuid",
    };

    const result = attachToParent(span, headers);

    expect(result).toBe(true);
    expect(span.setAttributes).toHaveBeenCalledTimes(1);
    const attrs = span.setAttributes.mock.calls[0]?.[0] as Record<
      string,
      string
    >;
    expect(attrs["opik.trace_id"]).toBe(TRACE_ID);
    expect(attrs["opik.parent_span_id"]).toBeUndefined();
    expect(isValidUuidV7(attrs[OPIK_SPAN_ID])).toBe(true);
    expect(warnSpy).toHaveBeenCalledTimes(1);
  });
});