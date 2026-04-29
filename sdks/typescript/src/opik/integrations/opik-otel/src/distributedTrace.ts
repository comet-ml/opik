import { generateId, logger } from "opik";
import { OPIK_SPAN_ID } from "./attributes";
import {
  OPIK_PARENT_SPAN_ID_HEADER,
  OPIK_TRACE_ID_HEADER,
  isValidUuidV7,
} from "./internal";
import { OpikDistributedTraceAttributes } from "./OpikDistributedTraceAttributes";

/**
 * Minimal structural type for an OpenTelemetry `Span`. Only the
 * `setAttributes` method is required for distributed-trace propagation, so we
 * avoid taking a runtime dependency on `@opentelemetry/api`. Any object that
 * implements this method — including `import("@opentelemetry/api").Span` — is
 * accepted.
 */
export interface OpenTelemetrySpanLike {
  setAttributes(attributes: Record<string, string>): unknown;
}

/**
 * Headers carrying Opik distributed trace context. Accepts both plain
 * dictionaries and any iterable of `[name, value]` pairs (e.g. the WHATWG
 * `Headers` object).
 */
export type HttpHeadersLike =
  | Record<string, string | string[] | undefined>
  | Iterable<[string, string]>;

function getHeader(
  headers: HttpHeadersLike,
  name: string
): string | undefined {
  const target = name.toLowerCase();
  if (
    typeof (headers as Iterable<[string, string]>)[Symbol.iterator] ===
    "function"
  ) {
    for (const [key, value] of headers as Iterable<[string, string]>) {
      if (key.toLowerCase() === target) {
        return value;
      }
    }
    return undefined;
  }
  const record = headers as Record<string, string | string[] | undefined>;
  for (const [key, value] of Object.entries(record)) {
    if (key.toLowerCase() === target) {
      return Array.isArray(value) ? value[0] : value;
    }
  }
  return undefined;
}

/**
 * Extracts Opik distributed trace attributes from HTTP headers.
 *
 * Reads `opik_trace_id` and the optional `opik_parent_span_id` headers
 * (case-insensitive). Values are trimmed; if `opik_trace_id` is missing,
 * empty, whitespace-only, or not a valid UUID the function logs a warning
 * (when invalid, or when `opik_parent_span_id` was provided without a
 * `opik_trace_id`) and returns `null`. If `opik_parent_span_id` is present
 * but not a valid UUID it is dropped with a warning and the trace is still
 * attached using the valid `opik_trace_id`.
 */
export function extractOpikDistributedTraceAttributes(
  httpHeaders: HttpHeadersLike
): OpikDistributedTraceAttributes | null {
  const traceId = getHeader(httpHeaders, OPIK_TRACE_ID_HEADER)?.trim();
  let parentSpanId =
    getHeader(httpHeaders, OPIK_PARENT_SPAN_ID_HEADER)?.trim() || undefined;

  if (!traceId) {
    if (parentSpanId !== undefined) {
      logger.warn(
        `Opik distributed trace header '${OPIK_TRACE_ID_HEADER}' is missing while '${OPIK_PARENT_SPAN_ID_HEADER}' is provided; skipping distributed trace processing.`
      );
    }
    return null;
  }
  if (!isValidUuidV7(traceId)) {
    logger.warn(
      `Opik distributed trace header '${OPIK_TRACE_ID_HEADER}' is not a valid UUIDv7; skipping distributed trace processing.`
    );
    return null;
  }

  if (parentSpanId !== undefined && !isValidUuidV7(parentSpanId)) {
    logger.warn(
      `Opik distributed trace header '${OPIK_PARENT_SPAN_ID_HEADER}' is not a valid UUIDv7; ignoring parent span id.`
    );
    parentSpanId = undefined;
  }

  return new OpikDistributedTraceAttributes(traceId, parentSpanId);
}

/**
 * Attaches an Opik distributed trace parent to the provided OpenTelemetry
 * span by extracting trace context from HTTP headers and setting the
 * corresponding span attributes.
 *
 * @returns `true` if headers were found and attributes were set, `false`
 * otherwise.
 */
export function attachToParent(
  span: OpenTelemetrySpanLike,
  httpHeaders: HttpHeadersLike
): boolean {
  const distributedTraceAttributes =
    extractOpikDistributedTraceAttributes(httpHeaders);
  if (distributedTraceAttributes === null) {
    return false;
  }
  const attributes = distributedTraceAttributes.asAttributes();
  // Mint a stable opik.span_id for this boundary span so descendants picked up by
  // OpikSpanProcessor can chain through it (their opik.parent_span_id will reference
  // this value, and the backend uses opik.span_id verbatim — see OpenTelemetryMapper).
  attributes[OPIK_SPAN_ID] = generateId();
  span.setAttributes(attributes);
  return true;
}