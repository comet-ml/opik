import isFinite from "lodash/isFinite";
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

// Visibility rule: the Explain button is shown only when the cell holds a value
// worth explaining. Each builder returns null otherwise, and the cell wrapper
// then renders no button:
//   - error    → an error is present (`error_info`),
//   - cost     → a finite cost > 0,
//   - duration → a finite duration > 0.
// `isPositive` keeps the button off zero / NaN / Infinity (instrumentation
// artefacts like a 0ms span where start === end, or an uncosted row).
const isPositive = (value: unknown): value is number =>
  isFinite(value) && (value as number) > 0;

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
    if (!row.project_id || !isPositive(row.duration)) return null;
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
    if (!row.project_id || !isPositive(row.total_estimated_cost)) return null;
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
