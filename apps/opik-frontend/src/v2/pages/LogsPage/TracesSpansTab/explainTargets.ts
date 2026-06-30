import { ExplainKind } from "@/types/assistant-sidebar";
import { BaseTraceData, SPAN_TYPE } from "@/types/traces";
import {
  createExplainTargetBuilder,
  finiteOrNull,
} from "@/v2/pages/LogsPage/createExplainTargetBuilder";

// Row data available at runtime in the Traces/Spans table (Trace | Span); both
// always carry project_id. The column type is BaseTraceData, so the span-only
// fields are read defensively.
export type ExplainableRow = BaseTraceData & {
  project_id: string;
  type?: SPAN_TYPE;
  model?: string;
  provider?: string;
};

// All builders share the project_id guard and the target envelope via
// `createExplainTargetBuilder` (see that helper for the visibility rule: the
// displayed value is NOT gated, so duration/cost are explainable even at N/A;
// only error vetoes, by returning null when there's no `error_info`).
//
// Traces and Spans extract identical payloads — only the `kind` differs. The
// span builders pass `entityId: row.id` (the span id); the backend resolves the
// parent trace itself. Built from per-shape factories so the trace/span pairs
// can't drift.
const errorTargetBuilder = (kind: ExplainKind) =>
  createExplainTargetBuilder<ExplainableRow>(kind, (row) =>
    row.error_info
      ? {
          exception_type: row.error_info.exception_type,
          message: row.error_info.message,
          traceback: row.error_info.traceback,
        }
      : null,
  );

const durationTargetBuilder = (kind: ExplainKind) =>
  createExplainTargetBuilder<ExplainableRow>(kind, (row) => ({
    duration: finiteOrNull(row.duration),
  }));

const costTargetBuilder = (kind: ExplainKind) =>
  createExplainTargetBuilder<ExplainableRow>(kind, (row) => {
    const payload: Record<string, unknown> = {
      total_estimated_cost: finiteOrNull(row.total_estimated_cost),
    };
    if (row.model) payload.model = row.model;
    if (row.provider) payload.provider = row.provider;
    if (row.type) payload.span_type = row.type;
    return payload;
  });

export const buildErrorTarget = errorTargetBuilder("trace.error");
export const buildDurationTarget = durationTargetBuilder("trace.duration");
export const buildCostTarget = costTargetBuilder("trace.cost");

export const buildSpanErrorTarget = errorTargetBuilder("span.error");
export const buildSpanDurationTarget = durationTargetBuilder("span.duration");
export const buildSpanCostTarget = costTargetBuilder("span.cost");
