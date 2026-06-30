import isFinite from "lodash/isFinite";
import { ExplainKind, ExplainTarget } from "@/types/assistant-sidebar";

/**
 * Coerce a metric value to a JSON-safe, backend-acceptable `number | null` for
 * an explain payload.
 *
 * `JSON.stringify` DROPS object keys whose value is `undefined`, and the console
 * serializes the payload to JSON when it calls the assistant backend. A
 * missing-cost / N/A-duration row left as `{ total_estimated_cost: undefined }`
 * therefore reaches the backend as `{}`, which rejects it with "Invalid payload
 * for '<kind>'". Sending an explicit `null` (which survives serialization)
 * keeps the key present so the "why is there no cost/duration here?" explain
 * still runs.
 *
 * Returns `null` for anything that isn't a usable, backend-valid metric, so the
 * backend's `float | None` (with `ge=0`) never 422s on a value it would reject:
 *  - undefined / null / NaN / Infinity → null (no value);
 *  - negative numbers → null (backend requires `>= 0`; a negative duration/cost
 *    is corrupt data — "not recorded" is truer than a 422);
 *  - numeric strings (a non-conforming API response — the TS type says `number`)
 *    are coerced, preserving the pre-coercion behaviour where the backend parsed
 *    `"5"` rather than us collapsing a real value to "not recorded".
 * A real number `>= 0` (including 0) passes through unchanged.
 */
export const finiteOrNull = (value: unknown): number | null => {
  const n = typeof value === "string" ? Number(value) : value;
  return isFinite(n) && (n as number) >= 0 ? (n as number) : null;
};

/**
 * Shared factory behind every per-cell explain target builder (Traces/Spans in
 * `TracesSpansTab/explainTargets.ts` and threads in `ThreadsTab/explainTargets.ts`).
 *
 * It owns the two things those builders must agree on so they can't drift:
 *  - the single structural visibility rule — a row is explainable iff it has a
 *    `project_id` (entityId + projectId scope the backend call);
 *  - the fixed `{ kind, entityId, projectId, payload }` envelope.
 *
 * `buildPayload` owns the kind-specific part. It returns the payload object, or
 * `null` to veto the target. Vetoing is explicit, never inferred from empty
 * values: duration/cost are intentionally explainable even when N/A or 0 ("why
 * is there no cost/duration here?" is a useful question). An N/A value must be
 * sent as an explicit `null` (via `finiteOrNull`), never `undefined` — see that
 * helper for why. A builder returns `null` from `buildPayload` on purpose to
 * suppress the button (e.g. an error cell with no `error_info`, which renders
 * nothing).
 *
 * Lives under `LogsPage/` rather than the comet `explain` plugin on purpose:
 * the OSS table code builds these targets and hands them to the plugin-provided
 * button via the registry, so it must not import from `plugins/comet`.
 */
export const createExplainTargetBuilder =
  <TRow extends { id: string; project_id?: string }>(
    kind: ExplainKind,
    buildPayload: (row: TRow) => Record<string, unknown> | null,
  ) =>
  (row: TRow): ExplainTarget | null => {
    if (!row.project_id) return null;
    const payload = buildPayload(row);
    if (!payload) return null;
    return {
      kind,
      entityId: row.id,
      projectId: row.project_id,
      payload,
    };
  };
