import { ExplainKind, ExplainTarget } from "@/types/assistant-sidebar";
import { BaseTraceData, SPAN_TYPE } from "@/types/traces";

// Row data available at runtime in the Traces/Spans table (Trace | Span); both
// always carry project_id. The column type is BaseTraceData, so the span-only
// fields are read defensively.
export type ExplainableRow = BaseTraceData & {
  project_id: string;
  type?: SPAN_TYPE;
  model?: string;
  provider?: string;
};

// Visibility rule: the Explain button is shown whenever the row is structurally
// explainable — it has a project_id (entityId + projectId scope the backend
// call). The displayed value is intentionally NOT gated: duration/cost are
// explainable even when N/A or 0, because "why is there no cost/duration here?"
// is itself a useful question (missing usage data, a 0ms span, an uncosted row).
// Error is the one exception — an empty error cell renders nothing and has no
// error to explain, so `buildErrorTarget` still requires `error_info`.

// Traces and Spans extract identical payloads — only the `kind` differs. The
// span builders pass `entityId: row.id` (the span id); the backend resolves the
// parent trace itself. Built from factories so the trace/span pairs can't drift.
const errorTargetBuilder =
  (kind: ExplainKind) =>
  (row: ExplainableRow): ExplainTarget | null => {
    if (!row.project_id || !row.error_info) return null;
    return {
      kind,
      entityId: row.id,
      projectId: row.project_id,
      payload: {
        exception_type: row.error_info.exception_type,
        message: row.error_info.message,
        traceback: row.error_info.traceback,
      },
    };
  };

const durationTargetBuilder =
  (kind: ExplainKind) =>
  (row: ExplainableRow): ExplainTarget | null => {
    if (!row.project_id) return null;
    return {
      kind,
      entityId: row.id,
      projectId: row.project_id,
      payload: { duration: row.duration },
    };
  };

const costTargetBuilder =
  (kind: ExplainKind) =>
  (row: ExplainableRow): ExplainTarget | null => {
    if (!row.project_id) return null;
    const payload: Record<string, unknown> = {
      total_estimated_cost: row.total_estimated_cost,
    };
    if (row.model) payload.model = row.model;
    if (row.provider) payload.provider = row.provider;
    if (row.type) payload.span_type = row.type;
    return {
      kind,
      entityId: row.id,
      projectId: row.project_id,
      payload,
    };
  };

export const buildErrorTarget = errorTargetBuilder("trace.error");
export const buildDurationTarget = durationTargetBuilder("trace.duration");
export const buildCostTarget = costTargetBuilder("trace.cost");

export const buildSpanErrorTarget = errorTargetBuilder("span.error");
export const buildSpanDurationTarget = durationTargetBuilder("span.duration");
export const buildSpanCostTarget = costTargetBuilder("span.cost");
