import isNumber from "lodash/isNumber";
import { ExplainTarget } from "@/types/assistant-sidebar";
import { BaseTraceData, SPAN_TYPE } from "@/types/traces";

// Row data available at runtime in the Traces table (Trace | Span). The column
// type is BaseTraceData; project_id and the span-only fields are read defensively.
export type ExplainableRow = BaseTraceData & {
  project_id?: string;
  type?: SPAN_TYPE;
  model?: string;
  provider?: string;
};

export const buildErrorTarget = (row: ExplainableRow): ExplainTarget | null => {
  if (!row.project_id || !row.error_info) return null;
  return {
    kind: "trace.error",
    entityId: row.id,
    projectId: row.project_id,
    payload: {
      exception_type: row.error_info.exception_type,
      message: row.error_info.message,
      traceback: row.error_info.traceback,
    },
  };
};

export const buildDurationTarget = (
  row: ExplainableRow,
): ExplainTarget | null => {
  if (!row.project_id || !isNumber(row.duration)) return null;
  return {
    kind: "trace.duration",
    entityId: row.id,
    projectId: row.project_id,
    payload: { duration: row.duration },
  };
};

export const buildCostTarget = (row: ExplainableRow): ExplainTarget | null => {
  if (
    !row.project_id ||
    !isNumber(row.total_estimated_cost) ||
    row.total_estimated_cost <= 0
  ) {
    return null;
  }
  const payload: Record<string, unknown> = {
    total_estimated_cost: row.total_estimated_cost,
  };
  if (row.model) payload.model = row.model;
  if (row.provider) payload.provider = row.provider;
  if (row.type) payload.span_type = row.type;
  return {
    kind: "trace.cost",
    entityId: row.id,
    projectId: row.project_id,
    payload,
  };
};
