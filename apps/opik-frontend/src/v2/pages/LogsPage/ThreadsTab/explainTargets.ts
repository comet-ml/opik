import isFinite from "lodash/isFinite";
import { ExplainTarget } from "@/types/assistant-sidebar";
import { Thread } from "@/types/traces";

// Visibility rule mirrors the Traces/Spans builders: show the Explain button
// whenever the row is structurally explainable (it has a project_id). The
// displayed value is not gated — N/A or 0 duration/cost is still explainable.
// Threads have no per-row error, so only duration and cost are explainable
// here. entityId is the thread id; projectId scopes it.
export const buildThreadDurationTarget = (
  row: Thread,
): ExplainTarget | null => {
  if (!row.project_id) return null;
  const payload: Record<string, unknown> = { duration: row.duration };
  if (isFinite(row.number_of_messages)) {
    payload.number_of_messages = row.number_of_messages;
  }
  return {
    kind: "thread.duration",
    entityId: row.id,
    projectId: row.project_id,
    payload,
  };
};

export const buildThreadCostTarget = (row: Thread): ExplainTarget | null => {
  if (!row.project_id) return null;
  const payload: Record<string, unknown> = {
    total_estimated_cost: row.total_estimated_cost,
  };
  if (isFinite(row.number_of_messages)) {
    payload.number_of_messages = row.number_of_messages;
  }
  return {
    kind: "thread.cost",
    entityId: row.id,
    projectId: row.project_id,
    payload,
  };
};
