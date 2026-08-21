import { randomBytes } from 'node:crypto';

/**
 * Builds an OTLP/JSON `ExportTraceServiceRequest` body for
 * `POST /v1/private/otel/v1/traces`.
 *
 * OTel ingestion has no SDK surface in this suite — the Python bridge and the
 * TS `Opik` client both write through the native span API, which bypasses
 * `OpenTelemetryMapper` entirely. A test about how OTel attributes are mapped
 * therefore has to speak OTLP itself, and this module is the one place that
 * knows the wire format.
 *
 * Two encoding details the backend is strict about (`OtelJsonMessageBodyReader`
 * hands the body to `JsonFormat.parser()` with no `ignoringUnknownFields`):
 *
 * - `trace_id` / `span_id` are protobuf `bytes`, so they go over JSON as
 *   **base64**, not the hex spelling the OTLP/JSON spec uses. Hex parses as
 *   base64 garbage rather than failing, which would silently scramble the ids.
 * - 64-bit fields (`startTimeUnixNano`, `AnyValue.intValue`) are JSON
 *   **strings**; a number literal loses precision above 2^53 and is rejected.
 */

/** One OTel attribute value, restricted to the two types these tests emit. */
export type OtelAttributeValue = string | number;

export interface OtelSpanSeed {
  /** OTel span name; Opik stores it as the span (and root trace) name. */
  name: string;
  /** Attribute key -> value, in the order `OpenTelemetryMapper` will walk them. */
  attributes: Record<string, OtelAttributeValue>;
}

interface OtelAnyValue {
  stringValue?: string;
  intValue?: string;
}

function anyValue(value: OtelAttributeValue): OtelAnyValue {
  return typeof value === 'number' ? { intValue: String(value) } : { stringValue: value };
}

/** Random OTel id: 16 bytes for a trace, 8 for a span, base64 as protobuf requires. */
function otelId(byteLength: number): string {
  return randomBytes(byteLength).toString('base64');
}

/**
 * Every seed becomes a root span in its own trace. Keeping them unparented is
 * deliberate: the mapper resolves provider/model/cost per span, so sharing a
 * parent would add a nesting variable these tests don't exercise, and one
 * trace per case makes a failing case addressable on its own in the UI.
 *
 * `startTime` is caller-supplied rather than read from the clock here so a
 * whole batch shares one instant — the Logs date window filters on it, and a
 * batch straddling a boundary would drop rows for reasons unrelated to mapping.
 */
export function buildOtelTraceRequest(
  spans: OtelSpanSeed[],
  startTime: Date,
  scopeName = 'opik-e2e',
): Record<string, unknown> {
  const startNanos = BigInt(startTime.getTime()) * 1_000_000n;
  const durationNanos = 1_000_000_000n;

  return {
    resourceSpans: [
      {
        resource: { attributes: [] },
        scopeSpans: [
          {
            scope: { name: scopeName },
            spans: spans.map((span) => ({
              traceId: otelId(16),
              spanId: otelId(8),
              name: span.name,
              // SPAN_KIND_CLIENT — what GenAI instrumentations emit for an
              // outbound inference or tool call.
              kind: 3,
              startTimeUnixNano: startNanos.toString(),
              endTimeUnixNano: (startNanos + durationNanos).toString(),
              attributes: Object.entries(span.attributes).map(([key, value]) => ({
                key,
                value: anyValue(value),
              })),
            })),
          },
        ],
      },
    ],
  };
}
