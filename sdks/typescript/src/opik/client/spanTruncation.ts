import { logger } from "@/utils/logger";

/**
 * Per-span payload-size enforcement (parity with the Python SDK, OPIK-7335).
 *
 * A span whose fields (`input`/`output`/`metadata`) are very large - e.g. an
 * entire retrieval result set logged inline - inflates into a multi-GB structure
 * on the backend and can destabilise ingestion. This enforces a per-span size
 * limit with a simple, predictable two-pass rule:
 *   1. truncate any field that on its own exceeds the limit (common case: one
 *      giant field; small siblings kept);
 *   2. if the span is still over the limit as a whole, truncate the remaining
 *      fields too - so the span is guaranteed to end up under the limit.
 *
 * The oversized field is replaced with a compact marker; a warning is logged.
 * Non-mutating: returns a shallow copy with the markers applied.
 */

const TRUNCATABLE_FIELDS = ["input", "output", "metadata"] as const;
type TruncatableField = (typeof TRUNCATABLE_FIELDS)[number];

// Minimal shape the truncation works on - satisfied by both SavedSpan (create) and the
// span update payload. Constraining to this (rather than Record<string, unknown>) keeps
// interface types like SavedSpan assignable.
type SpanLike = { input?: unknown; output?: unknown; metadata?: unknown };

const BYTES_PER_MB = 1024 * 1024;

interface TruncationMarker {
  // snake_case: this is wire data stored on the span, matching the backend / Python SDK marker.
  opik_truncated: true;
  reason: string;
}

const truncationMarker = (sizeMb: number): TruncationMarker => ({
  opik_truncated: true,
  reason: `<omitted_due_to_size_${Math.round(sizeMb)}MB_error_code_413_400>`,
});

// Serialized size of a value in MB - what it would weigh on the wire. Fail-safe: if the value
// can't be serialized (circular reference, BigInt, ...) we can't measure it, so return 0 and
// leave it untouched. Truncation must never throw and break span creation.
const fieldSizeMb = (value: unknown): number => {
  try {
    const json = JSON.stringify(value);
    return json ? Buffer.byteLength(json, "utf8") / BYTES_PER_MB : 0;
  } catch {
    return 0;
  }
};

/**
 * Return a shallow copy of `span` with oversized fields replaced by a truncation
 * marker (or `span` unchanged), plus the list of fields that were truncated.
 */
export const truncateSpanFields = <T extends SpanLike>(
  span: T,
  maxSizeMb: number,
): { result: T; truncated: TruncatableField[] } => {
  const sizes = {} as Record<TruncatableField, number>;
  for (const field of TRUNCATABLE_FIELDS) {
    if (span[field] != null) {
      sizes[field] = fieldSizeMb(span[field]);
    }
  }
  if (Object.keys(sizes).length === 0) {
    return { result: span, truncated: [] };
  }

  const overrides: Partial<Record<TruncatableField, TruncationMarker>> = {};

  // Pass 1 - fields individually over the limit (the common "one giant field").
  for (const field of TRUNCATABLE_FIELDS) {
    if (sizes[field] !== undefined && sizes[field] > maxSizeMb) {
      overrides[field] = truncationMarker(sizes[field]);
    }
  }

  // Pass 2 - hard per-span cap: if the span is still over as a whole, truncate
  // the remaining truncatable fields too. One measurement, no loop.
  if (fieldSizeMb({ ...span, ...overrides }) > maxSizeMb) {
    for (const field of TRUNCATABLE_FIELDS) {
      if (sizes[field] !== undefined && overrides[field] === undefined) {
        overrides[field] = truncationMarker(sizes[field]);
      }
    }
  }

  const truncated = Object.keys(overrides) as TruncatableField[];
  if (truncated.length === 0) {
    return { result: span, truncated: [] };
  }
  return { result: { ...span, ...overrides } as T, truncated };
};

/**
 * Truncate a span-like payload (create or update) if its fields exceed the
 * per-span limit, logging a warning. Returns a shallow copy (or the input
 * unchanged). A `maxSizeMb <= 0` disables the check.
 */
export const truncateSpanIfNeeded = <T extends SpanLike>(
  span: T,
  maxSizeMb: number,
  spanId?: string,
): T => {
  if (!maxSizeMb || maxSizeMb <= 0) {
    return span;
  }
  const { result, truncated } = truncateSpanFields(span, maxSizeMb);
  if (truncated.length > 0) {
    logger.warn(
      `Span '${spanId ?? "unknown"}' exceeded the per-span size limit of ${maxSizeMb} MB; ` +
        `truncated field(s): ${truncated.join(", ")}. ` +
        `Log large payloads as attachments to avoid truncation.`,
    );
  }
  return result;
};
