import { Context, propagation, trace, Span } from "@opentelemetry/api";
import { ReadableSpan, SpanProcessor } from "@opentelemetry/sdk-trace-base";

import { generateId } from "@/utils/generateId";
import { logger } from "@/utils/logger";
import {
  OPIK_PARENT_SPAN_ID,
  OPIK_SPAN_ID,
  OPIK_TRACE_ID,
} from "./attributes";

interface InheritedContext {
  traceId: string;
  parentSpanId?: string;
}

/**
 * Resolves the Opik trace context that a new span should inherit. Returns the
 * trace_id (and optionally a parent_span_id) when the parent OTel span — or, for
 * cross-process boundaries, OTel baggage — already carries Opik IDs. Returns
 * `undefined` when the new span has no Opik ancestor and should be left alone.
 */
function resolveInherited(parentContext: Context): InheritedContext | undefined {
  // 1) In-process: pull from the parent OTel span's attributes. The parent must
  // carry both opik.trace_id and opik.span_id — this pair is set together by
  // attachToParent on the boundary and by this processor on every inherited
  // descendant, so a parent that has trace_id but not span_id means a misconfigured
  // upstream, and we don't try to guess.
  const parentSpan = trace.getSpan(parentContext);
  if (parentSpan && "attributes" in parentSpan) {
    const attrs = (parentSpan as unknown as ReadableSpan).attributes ?? {};
    const parentTraceId = attrs[OPIK_TRACE_ID];
    const parentSpanId = attrs[OPIK_SPAN_ID];
    if (
      typeof parentTraceId === "string" &&
      parentTraceId !== "" &&
      typeof parentSpanId === "string" &&
      parentSpanId !== ""
    ) {
      return { traceId: parentTraceId, parentSpanId };
    }
  }

  // 2) Cross-process: pull from OTel baggage (propagated via the W3C `baggage`
  // header). Used when a child process inherits OTel context from an upstream
  // service that already had Opik IDs.
  const baggage = propagation.getBaggage(parentContext);
  const baggageTraceId = baggage?.getEntry(OPIK_TRACE_ID)?.value;
  if (typeof baggageTraceId === "string" && baggageTraceId !== "") {
    const baggageParentSpanId = baggage?.getEntry(OPIK_SPAN_ID)?.value;
    return {
      traceId: baggageTraceId,
      parentSpanId:
        typeof baggageParentSpanId === "string" && baggageParentSpanId !== ""
          ? baggageParentSpanId
          : undefined,
    };
  }

  return undefined;
}

/**
 * OTel `SpanProcessor` that propagates Opik IDs down an attached subtree.
 *
 * `attachToParent` only sets `opik.trace_id` / `opik.parent_span_id` /
 * `opik.span_id` on the *boundary* OTel span. Children created inside that
 * span inherit OTel context but not those attributes, so without this
 * processor descendants would be orphaned in a synthetic Opik trace by the
 * backend.
 *
 * On every span start this processor inspects the parent OTel span (and OTel
 * baggage, for cross-process boundaries). If the parent already carries
 * `opik.trace_id` + `opik.span_id`, it mints a fresh `opik.span_id` for the
 * new span and threads the parent's value as `opik.parent_span_id`. The
 * backend `OpenTelemetryMapper` fast path then uses these UUIDs verbatim.
 *
 * Spans whose parent has no Opik attributes (and no baggage) are left
 * untouched, so today's SHA-256 + Redis path still applies — the processor
 * only kicks in for attached subtrees originating from `attachToParent`.
 *
 * @example
 * ```ts
 * import { BasicTracerProvider } from "@opentelemetry/sdk-trace-base";
 * import { OpikSpanProcessor } from "opik";
 *
 * const provider = new BasicTracerProvider();
 * provider.addSpanProcessor(new OpikSpanProcessor());
 * // ... add your exporter processor as well
 * ```
 */
export class OpikSpanProcessor implements SpanProcessor {
  onStart(span: Span, parentContext: Context): void {
    const inherited = resolveInherited(parentContext);
    if (!inherited) {
      return;
    }

    try {
      span.setAttribute(OPIK_TRACE_ID, inherited.traceId);
      span.setAttribute(OPIK_SPAN_ID, generateId());
      if (inherited.parentSpanId !== undefined) {
        span.setAttribute(OPIK_PARENT_SPAN_ID, inherited.parentSpanId);
      }
    } catch (err) {
      // Per OTel contract, onStart must not throw. Log and swallow.
      logger.debug("OpikSpanProcessor.onStart failed:", err);
    }
  }

  onEnd(): void {
    // no-op
  }

  async forceFlush(): Promise<void> {
    // no-op
  }

  async shutdown(): Promise<void> {
    // no-op
  }
}