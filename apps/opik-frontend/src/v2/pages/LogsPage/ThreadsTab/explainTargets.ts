import isFinite from "lodash/isFinite";
import { Thread } from "@/types/traces";
import {
  createExplainTargetBuilder,
  finiteOrNull,
} from "@/v2/pages/LogsPage/createExplainTargetBuilder";

// Threads have no per-row error, so only duration and cost are explainable
// here. entityId is the thread id; projectId scopes it. Both builders share the
// project_id guard + target envelope via `createExplainTargetBuilder` (see that
// helper for the visibility rule — N/A or 0 duration/cost is still explainable)
// and differ only in their value field, so they can't drift from each other or
// from the Traces/Spans builders.

// Thread targets additionally attach number_of_messages when present; kept in
// one place so duration and cost stay in lockstep.
const withMessageCount = (
  row: Thread,
  payload: Record<string, unknown>,
): Record<string, unknown> => {
  if (isFinite(row.number_of_messages)) {
    payload.number_of_messages = row.number_of_messages;
  }
  return payload;
};

export const buildThreadDurationTarget = createExplainTargetBuilder<Thread>(
  "thread.duration",
  (row) => withMessageCount(row, { duration: finiteOrNull(row.duration) }),
);

export const buildThreadCostTarget = createExplainTargetBuilder<Thread>(
  "thread.cost",
  (row) =>
    withMessageCount(row, {
      total_estimated_cost: finiteOrNull(row.total_estimated_cost),
    }),
);
