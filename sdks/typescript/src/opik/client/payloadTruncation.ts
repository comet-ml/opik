import { logger } from "@/utils/logger";

/**
 * Per-object payload-size enforcement for spans and traces (parity with the
 * Python SDK, OPIK-7335).
 *
 * A span or trace whose `input`/`output` fields are very large - e.g. an entire
 * retrieval result set logged inline - inflates into a multi-GB structure on the
 * backend and can destabilise ingestion. This enforces a per-object size limit
 * with a simple, predictable two-pass rule:
 *   1. truncate any field that on its own exceeds the limit (common case: one
 *      giant field; small siblings kept);
 *   2. if input+output together are still over the limit, truncate the remaining
 *      truncatable field too.
 *
 * `metadata` is deliberately never truncated (it holds small structured fields
 * consumers rely on) and is excluded from the whole-object measurement, so a large
 * metadata can't trigger truncation of input/output. The oversized field is
 * replaced with a compact marker; a warning is logged. Non-mutating: returns a
 * shallow copy with the markers applied.
 */

// Only input/output are truncatable; metadata is exempt (see the note above).
const TRUNCATABLE_FIELDS = ["input", "output"] as const;
type TruncatableField = (typeof TRUNCATABLE_FIELDS)[number];

// Minimal shape the truncation works on - satisfied by SavedSpan / SavedTrace (create)
// and their update payloads. Constraining to this (rather than Record<string, unknown>)
// keeps interface types like SavedSpan/SavedTrace assignable.
type PayloadLike = { input?: unknown; output?: unknown; metadata?: unknown };

// What kind of object is being truncated - used only for the log message.
type PayloadKind = "span" | "trace";

const BYTES_PER_MB = 1024 * 1024;

interface TruncationMarker {
  // snake_case: this is wire data stored on the object, matching the backend / Python SDK marker.
  opik_truncated: true;
  reason: string;
}

const truncationMarker = (sizeMb: number): TruncationMarker => ({
  opik_truncated: true,
  reason: `<omitted_due_to_size_${Math.round(sizeMb)}MB_error_code_413_400>`,
});

// V8 caps a single string at ~512 MiB; JSON.stringify throws RangeError when its output would
// exceed that. Such a field is at least this large, so we report this lower bound (rather than
// 0) to force truncation of the very multi-GB payloads this guard exists to catch.
const MAX_SERIALIZABLE_MB = 512;

// Serialized size of a value in MB - what it would weigh on the wire. A RangeError means the
// value is too large to serialize (see above) and must be truncated. Any other failure
// (circular reference, BigInt, ...) is genuinely unmeasurable, so return 0 and leave it
// untouched. Truncation must never throw and break span/trace creation.
const fieldSizeMb = (value: unknown): number => {
  try {
    const json = JSON.stringify(value);
    return json ? Buffer.byteLength(json, "utf8") / BYTES_PER_MB : 0;
  } catch (error) {
    return error instanceof RangeError ? MAX_SERIALIZABLE_MB : 0;
  }
};

/**
 * Return a shallow copy of `payload` with oversized fields replaced by a truncation
 * marker (or `payload` unchanged), plus the list of fields that were truncated.
 */
export const truncatePayloadFields = <T extends PayloadLike>(
  payload: T,
  maxSizeMb: number,
): { result: T; truncated: TruncatableField[] } => {
  const sizes = {} as Record<TruncatableField, number>;
  for (const field of TRUNCATABLE_FIELDS) {
    if (payload[field] != null) {
      sizes[field] = fieldSizeMb(payload[field]);
    }
  }
  if (Object.keys(sizes).length === 0) {
    return { result: payload, truncated: [] };
  }

  const overrides: Partial<Record<TruncatableField, TruncationMarker>> = {};

  // Pass 1 - fields individually over the limit (the common "one giant field").
  for (const field of TRUNCATABLE_FIELDS) {
    if (sizes[field] !== undefined && sizes[field] > maxSizeMb) {
      overrides[field] = truncationMarker(sizes[field]);
    }
  }

  // Pass 2 - hard per-object cap: if input+output are still over as a whole,
  // truncate the remaining truncatable field too. One measurement, no loop.
  // metadata is excluded so it can't drag the object over and cut small siblings.
  if (fieldSizeMb({ ...payload, ...overrides, metadata: undefined }) > maxSizeMb) {
    for (const field of TRUNCATABLE_FIELDS) {
      if (sizes[field] !== undefined && overrides[field] === undefined) {
        overrides[field] = truncationMarker(sizes[field]);
      }
    }
  }

  const truncated = Object.keys(overrides) as TruncatableField[];
  if (truncated.length === 0) {
    return { result: payload, truncated: [] };
  }
  return { result: { ...payload, ...overrides } as T, truncated };
};

/**
 * Truncate a span/trace payload (create or update) if its fields exceed the
 * per-object limit, logging a warning. Returns a shallow copy (or the input
 * unchanged). A `maxSizeMb <= 0` disables the check. `kind`/`id` are used only
 * for the log message.
 */
export const truncatePayloadIfNeeded = <T extends PayloadLike>(
  payload: T,
  maxSizeMb: number,
  kind: PayloadKind = "span",
  id?: string,
): T => {
  if (!maxSizeMb || maxSizeMb <= 0) {
    return payload;
  }
  const { result, truncated } = truncatePayloadFields(payload, maxSizeMb);
  if (truncated.length > 0) {
    const label = kind.charAt(0).toUpperCase() + kind.slice(1);
    logger.warn(
      `${label} '${id ?? "unknown"}' exceeded the payload size limit of ${maxSizeMb} MB; ` +
        `truncated field(s): ${truncated.join(", ")}. ` +
        `Log large payloads as attachments to avoid truncation.`,
    );
  }
  return result;
};
