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

// Reported as the size for ANY value we can't serialize here: a RangeError (a string over V8's
// ~512 MiB cap) or a TypeError (a circular reference, BigInt, ...). If JSON.stringify fails here it
// will fail on the wire too, so forcing truncation degrades the field to a marker and keeps
// span/trace creation from throwing downstream on an unsendable payload - truncation must never
// break creation, and leaving the value in place (size 0) would only defer the crash to send time.
const MAX_SERIALIZABLE_MB = 512;

// Serialized size of a value in MB - what it would weigh on the wire (0 if it serializes to
// nothing). Any serialization failure is treated as oversized (see MAX_SERIALIZABLE_MB).
const fieldSizeMb = (value: unknown): number => {
  try {
    const json = JSON.stringify(value);
    return json ? Buffer.byteLength(json, "utf8") / BYTES_PER_MB : 0;
  } catch {
    return MAX_SERIALIZABLE_MB;
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
