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
 * Returns `true` only when `value` parses as a valid UUID and its version is 7.
 * Mirrors `opik`'s own `isValidUuidV7` helper.
 */
export const isValidUuidV7 = (value: unknown): boolean => {
  if (typeof value !== "string" || !validate(value)) {
    return false;
  }
  return version(value) === 7;
};