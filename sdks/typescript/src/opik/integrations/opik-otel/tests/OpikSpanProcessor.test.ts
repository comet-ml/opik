import { describe, it, expect, beforeEach, afterEach } from "vitest";
import {
  context,
  propagation,
  trace,
  type Context,
  type Span as ApiSpan,
} from "@opentelemetry/api";
import {
  BasicTracerProvider,
  ReadableSpan,
} from "@opentelemetry/sdk-trace-base";
import {
  OpikSpanProcessor,
  attachToParent,
  OPIK_PARENT_SPAN_ID,
  OPIK_SPAN_ID,
  OPIK_TRACE_ID,
} from "../src/index";
import { isValidUuidV7 } from "../src/internal";

const TRACE_ID = "0193b3a5-1234-7abc-9def-0123456789ab";
const PARENT_SPAN_ID = "0193b3a5-5678-7abc-9def-0123456789cd";

const PARENT_OPIK_SPAN_ID = "0193b3a5-aaaa-7abc-9def-000000000001";
const BAGGAGE_OPIK_SPAN_ID = "0193b3a5-bbbb-7abc-9def-000000000002";

const attrsOf = (span: ApiSpan) => (span as unknown as ReadableSpan).attributes;

const ctxWithSpan = (span: ApiSpan, base: Context = context.active()) =>
  trace.setSpan(base, span);

describe("OpikSpanProcessor", () => {
  let provider: BasicTracerProvider;
  let tracer: ReturnType<BasicTracerProvider["getTracer"]>;

  beforeEach(() => {
    provider = new BasicTracerProvider({
      spanProcessors: [new OpikSpanProcessor()],
    });
    tracer = provider.getTracer("opik-span-processor-test");
  });

  afterEach(async () => {
    await provider.shutdown();
  });

  it("leaves a root span with no parent and no baggage untouched", () => {
    const span = tracer.startSpan("root");
    const attrs = attrsOf(span);
    span.end();

    expect(attrs[OPIK_TRACE_ID]).toBeUndefined();
    expect(attrs[OPIK_SPAN_ID]).toBeUndefined();
    expect(attrs[OPIK_PARENT_SPAN_ID]).toBeUndefined();
  });

  it("inherits and chains when the parent carries the full triple", () => {
    const parent = tracer.startSpan("parent");
    parent.setAttribute(OPIK_TRACE_ID, TRACE_ID);
    parent.setAttribute(OPIK_SPAN_ID, PARENT_OPIK_SPAN_ID);

    const child = tracer.startSpan("child", {}, ctxWithSpan(parent));
    const childAttrs = attrsOf(child);
    child.end();
    parent.end();

    expect(childAttrs[OPIK_TRACE_ID]).toBe(TRACE_ID);
    // child gets a freshly minted opik.span_id (UUIDv7, distinct from parent's)
    expect(isValidUuidV7(childAttrs[OPIK_SPAN_ID] as string)).toBe(true);
    expect(childAttrs[OPIK_SPAN_ID]).not.toBe(PARENT_OPIK_SPAN_ID);
    // child's opik.parent_span_id == parent's opik.span_id
    expect(childAttrs[OPIK_PARENT_SPAN_ID]).toBe(PARENT_OPIK_SPAN_ID);
  });

  it("does not inherit when the parent has trace_id without span_id", () => {
    // Misconfigured upstream — refuse to guess.
    const parent = tracer.startSpan("parent");
    parent.setAttribute(OPIK_TRACE_ID, TRACE_ID);
    parent.setAttribute(OPIK_PARENT_SPAN_ID, PARENT_SPAN_ID);

    const child = tracer.startSpan("child", {}, ctxWithSpan(parent));
    const childAttrs = attrsOf(child);
    child.end();
    parent.end();

    expect(childAttrs[OPIK_TRACE_ID]).toBeUndefined();
    expect(childAttrs[OPIK_SPAN_ID]).toBeUndefined();
    expect(childAttrs[OPIK_PARENT_SPAN_ID]).toBeUndefined();
  });

  it("leaves a child untouched when the parent has no Opik attrs", () => {
    const parent = tracer.startSpan("parent");
    const child = tracer.startSpan("child", {}, ctxWithSpan(parent));
    const childAttrs = attrsOf(child);
    child.end();
    parent.end();

    expect(childAttrs[OPIK_TRACE_ID]).toBeUndefined();
    expect(childAttrs[OPIK_SPAN_ID]).toBeUndefined();
  });

  it("inherits trace_id and parent_span_id from baggage (cross-process)", () => {
    const baggage = propagation
      .createBaggage()
      .setEntry(OPIK_TRACE_ID, { value: TRACE_ID })
      .setEntry(OPIK_SPAN_ID, { value: BAGGAGE_OPIK_SPAN_ID });
    const ctx = propagation.setBaggage(context.active(), baggage);

    const span = tracer.startSpan("from_baggage", {}, ctx);
    const spanAttrs = attrsOf(span);
    span.end();

    expect(spanAttrs[OPIK_TRACE_ID]).toBe(TRACE_ID);
    expect(isValidUuidV7(spanAttrs[OPIK_SPAN_ID] as string)).toBe(true);
    expect(spanAttrs[OPIK_PARENT_SPAN_ID]).toBe(BAGGAGE_OPIK_SPAN_ID);
  });

  it("inherits trace_id alone from baggage and leaves parent_span_id unset", () => {
    const baggage = propagation
      .createBaggage()
      .setEntry(OPIK_TRACE_ID, { value: TRACE_ID });
    const ctx = propagation.setBaggage(context.active(), baggage);

    const span = tracer.startSpan("from_baggage", {}, ctx);
    const spanAttrs = attrsOf(span);
    span.end();

    expect(spanAttrs[OPIK_TRACE_ID]).toBe(TRACE_ID);
    expect(isValidUuidV7(spanAttrs[OPIK_SPAN_ID] as string)).toBe(true);
    expect(spanAttrs[OPIK_PARENT_SPAN_ID]).toBeUndefined();
  });
});

describe("attachToParent + OpikSpanProcessor", () => {
  let provider: BasicTracerProvider;
  let tracer: ReturnType<BasicTracerProvider["getTracer"]>;

  beforeEach(() => {
    provider = new BasicTracerProvider({
      spanProcessors: [new OpikSpanProcessor()],
    });
    tracer = provider.getTracer("opik-span-processor-test");
  });

  afterEach(async () => {
    await provider.shutdown();
  });

  it("deep chain — each level references its immediate parent's opik.span_id", () => {
    const headers = {
      opik_trace_id: TRACE_ID,
      opik_parent_span_id: PARENT_SPAN_ID,
    };

    const boundary = tracer.startSpan("boundary");
    expect(attachToParent(boundary, headers)).toBe(true);
    const boundaryAttrs = attrsOf(boundary);
    const boundarySpanId = boundaryAttrs[OPIK_SPAN_ID] as string;

    // boundary now carries all three: trace_id (T_A), parent_span_id (P_A), and
    // a freshly minted span_id (S_B).
    expect(boundaryAttrs[OPIK_TRACE_ID]).toBe(TRACE_ID);
    expect(boundaryAttrs[OPIK_PARENT_SPAN_ID]).toBe(PARENT_SPAN_ID);

    const l1 = tracer.startSpan("level1", {}, ctxWithSpan(boundary));
    const l1Attrs = attrsOf(l1);
    const l1SpanId = l1Attrs[OPIK_SPAN_ID] as string;

    const l2 = tracer.startSpan("level2", {}, ctxWithSpan(l1));
    const l2Attrs = attrsOf(l2);

    l2.end();
    l1.end();
    boundary.end();

    // Each level references its immediate parent's opik.span_id.
    expect(l1Attrs[OPIK_PARENT_SPAN_ID]).toBe(boundarySpanId);
    expect(l2Attrs[OPIK_PARENT_SPAN_ID]).toBe(l1SpanId);

    // All share the same trace.
    expect(l1Attrs[OPIK_TRACE_ID]).toBe(TRACE_ID);
    expect(l2Attrs[OPIK_TRACE_ID]).toBe(TRACE_ID);

    // All UUIDs are valid v7.
    expect(isValidUuidV7(boundarySpanId)).toBe(true);
    expect(isValidUuidV7(l1SpanId)).toBe(true);
    expect(isValidUuidV7(l2Attrs[OPIK_SPAN_ID] as string)).toBe(true);
  });
});