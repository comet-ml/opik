import { Context, propagation, trace, Span } from "@opentelemetry/api";
import { ReadableSpan, SpanProcessor } from "@opentelemetry/sdk-trace-base";
import { generateId, logger } from "opik";

import {
  OPIK_PARENT_SPAN_ID,
  OPIK_SPAN_ID,
  OPIK_TRACE_ID,
} from "./attributes";
import { isValidUuidV7 } from "./internal";

interface InheritedContext {
  traceId: string;
  parentSpanId?: string;
}

/**
 * Resolves the Opik trace context that a new span should inherit. Returns the
 * trace_id (and optionally a parent_span_id) when the parent OTel span — or, for
 * cross-process boundaries, OTel baggage — already carries Opik IDs. Returns
 * `undefined` when the new span has no Opik ancestor and should be left alone.
 *
 * Validation mirrors the Python `_resolve_inherited`: every inherited ID is
 * required to be a valid UUIDv7. Present-but-invalid values are dropped with a
 * `logger.warn` so a misconfigured upstream is visible at runtime; absent
 * values are silent (the common case for spans outside an Opik subtree).
 */
function resolveInherited(
  parentContext: Context
): InheritedContext | undefined {
  // 1) In-process: pull from the parent OTel span's attributes. The parent must
  // carry both opik.trace_id and opik.span_id — this pair is set together by
  // attachToParent on the boundary and by this processor on every inherited
  // descendant, so a parent that has trace_id but not span_id means a
  // misconfigured upstream, and we don't try to guess.
  const parentSpan = trace.getSpan(parentContext);
  if (parentSpan && "attributes" in parentSpan) {
    const attrs = (parentSpan as unknown as ReadableSpan).attributes ?? {};
    const parentTraceId = attrs[OPIK_TRACE_ID];
    const parentSpanId = attrs[OPIK_SPAN_ID];

    // Both absent → parent isn't part of an Opik subtree; fall through to baggage.
    if (parentTraceId !== undefined || parentSpanId !== undefined) {
      if (!isValidUuidV7(parentTraceId)) {
        logger.warn(
          `Parent span attribute '${OPIK_TRACE_ID}' is missing or not a valid UUIDv7: ${JSON.stringify(parentTraceId)}; ignoring inherited Opik context.`
        );
      } else if (!isValidUuidV7(parentSpanId)) {
        logger.warn(
          `Parent span attribute '${OPIK_SPAN_ID}' is missing or not a valid UUIDv7: ${JSON.stringify(parentSpanId)}; ignoring inherited Opik context.`
        );
      } else {
        return { traceId: parentTraceId, parentSpanId };
      }
    }
  }

  // 2) Cross-process: pull from OTel baggage (propagated via the W3C `baggage`
  // header). Used when a child process inherits OTel context from an upstream
  // service that already had Opik IDs.
  const baggage = propagation.getBaggage(parentContext);
  const baggageTraceId = baggage?.getEntry(OPIK_TRACE_ID)?.value;
  if (baggageTraceId === undefined) {
    // No Opik context in baggage — the common case for spans outside an
    // attached subtree. Leave the span untouched.
    return undefined;
  }
  if (!isValidUuidV7(baggageTraceId)) {
    logger.warn(
      `Baggage value for '${OPIK_TRACE_ID}' is not a valid UUIDv7: ${JSON.stringify(baggageTraceId)}; ignoring.`
    );
    return undefined;
  }

  const baggageParentSpanId = baggage?.getEntry(OPIK_SPAN_ID)?.value;
  let parentSpanId: string | undefined;
  if (baggageParentSpanId === undefined) {
    parentSpanId = undefined;
  } else if (!isValidUuidV7(baggageParentSpanId)) {
    logger.warn(
      `Baggage value for '${OPIK_SPAN_ID}' is not a valid UUIDv7: ${JSON.stringify(baggageParentSpanId)}; attaching to '${OPIK_TRACE_ID}' without a parent span id.`
    );
    parentSpanId = undefined;
  } else {
    parentSpanId = baggageParentSpanId;
  }

  return { traceId: baggageTraceId, parentSpanId };
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
 * import { OpikSpanProcessor } from "opik-otel";
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