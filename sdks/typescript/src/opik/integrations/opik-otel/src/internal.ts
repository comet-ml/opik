import { validate, version } from "uuid";

/**
 * Canonical Opik distributed-trace HTTP header keys. Inlined here (rather than
 * re-imported from `opik`) so that this integration package can be installed
 * against any reasonably recent version of `opik` without requiring those
 * symbols to already be exported by the core package.
 */
export const OPIK_TRACE_ID_HEADER = "opik_trace_id";
export const OPIK_PARENT_SPAN_ID_HEADER = "opik_parent_span_id";

/**
 * Returns `true` only when `value` is a string that parses as a UUID and whose
 * version is 7. The type predicate narrows `value` to `string` in callers'
 * truthy branches, mirroring `opik`'s own `isValidUuidV7` helper.
 */
export const isValidUuidV7 = (value: unknown): value is string => {
  if (typeof value !== "string" || !validate(value)) {
    return false;
  }
  return version(value) === 7;
};