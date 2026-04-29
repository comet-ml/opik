import { describe, it, expect } from "vitest";
import {
  OPIK_TRACE_ID_HEADER,
  OPIK_PARENT_SPAN_ID_HEADER,
  getDistributedTraceHeaders,
} from "@/context";
import { trackStorage } from "@/decorators/track";
import type { Span } from "@/tracer/Span";
import type { Trace } from "@/tracer/Trace";

const TRACE_ID = "0193b3a5-1234-7abc-9def-0123456789ab";
const SPAN_ID = "0193b3a5-5678-7abc-9def-0123456789cd";

const fakeTrace = { data: { id: TRACE_ID } } as unknown as Trace;
const fakeSpan = { data: { id: SPAN_ID } } as unknown as Span;

describe("context: header constants", () => {
  it("uses lowercase canonical header keys", () => {
    expect(OPIK_TRACE_ID_HEADER).toBe("opik_trace_id");
    expect(OPIK_PARENT_SPAN_ID_HEADER).toBe("opik_parent_span_id");
  });
});

describe("getDistributedTraceHeaders", () => {
  it("returns null when called outside a track context", () => {
    expect(getDistributedTraceHeaders()).toBeNull();
  });

  it("returns trace_id and parent_span_id from the active track context", () => {
    const headers = trackStorage.run(
      { trace: fakeTrace, span: fakeSpan },
      () => getDistributedTraceHeaders()
    );

    expect(headers).toEqual({
      [OPIK_TRACE_ID_HEADER]: TRACE_ID,
      [OPIK_PARENT_SPAN_ID_HEADER]: SPAN_ID,
    });
  });

  it("returns null when only span is set on the context store", () => {
    const headers = trackStorage.run({ span: fakeSpan }, () =>
      getDistributedTraceHeaders()
    );

    expect(headers).toBeNull();
  });

  it("returns null when only trace is set on the context store", () => {
    const headers = trackStorage.run({ trace: fakeTrace }, () =>
      getDistributedTraceHeaders()
    );

    expect(headers).toBeNull();
  });
});