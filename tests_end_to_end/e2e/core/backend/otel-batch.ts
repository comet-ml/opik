import { randomBytes } from 'node:crypto';

/**
 * One span to send to the OTLP ingest endpoint.
 *
 * `attributes` is the whole OTel attribute set for the span — a string lands as
 * an OTLP `stringValue`, a number as an `intValue`. The distinction is not
 * cosmetic: a *non-string* attribute value is one of the shapes the provider
 * mapping has to survive, so callers need a way to express it.
 */
export interface OtelSpanSeed {
  name: string;
  attributes: Record<string, string | number>;
}

/** OTLP/JSON `AnyValue`, narrowed to the two shapes this builder emits. */
type OtlpAnyValue = { stringValue: string } | { intValue: string };

interface OtlpSpan {
  traceId: string;
  spanId: string;
  name: string;
  kind: number;
  startTimeUnixNano: string;
  endTimeUnixNano: string;
  attributes: Array<{ key: string; value: OtlpAnyValue }>;
}

export interface OtlpTraceBatch {
  resourceSpans: Array<{ scopeSpans: Array<{ spans: OtlpSpan[] }> }>;
}

/** SPAN_KIND_CLIENT — what an instrumented outbound LLM call reports. */
const SPAN_KIND_CLIENT = 3;

const NANOS_PER_MS = 1_000_000n;

/**
 * Protobuf JSON encodes `bytes` fields as base64, which is what the backend's
 * `JsonFormat.parser()` expects — not the hex form the OTLP/JSON spec shows.
 */
function randomIdBase64(bytes: number): string {
  return randomBytes(bytes).toString('base64');
}

function toAnyValue(value: string | number): OtlpAnyValue {
  return typeof value === 'number' ? { intValue: String(value) } : { stringValue: value };
}

/**
 * Build one OTLP/JSON `ExportTraceServiceRequest` carrying the given spans.
 *
 * Every span gets its own trace id, so each arrives as a standalone trace — the
 * cases under test are independent of one another and a shared parent would let
 * one case's attributes reach another's span.
 *
 * Spans are stamped at the current time so they fall inside the Logs page's
 * default date range; nothing else in the payload depends on the clock.
 */
export function buildOtlpTraceBatch(spans: OtelSpanSeed[]): OtlpTraceBatch {
  const startNanos = BigInt(Date.now()) * NANOS_PER_MS;
  return {
    resourceSpans: [
      {
        scopeSpans: [
          {
            spans: spans.map((span) => ({
              traceId: randomIdBase64(16),
              spanId: randomIdBase64(8),
              name: span.name,
              kind: SPAN_KIND_CLIENT,
              startTimeUnixNano: String(startNanos),
              endTimeUnixNano: String(startNanos + NANOS_PER_MS),
              attributes: Object.entries(span.attributes).map(([key, value]) => ({
                key,
                value: toAnyValue(value),
              })),
            })),
          },
        ],
      },
    ],
  };
}
