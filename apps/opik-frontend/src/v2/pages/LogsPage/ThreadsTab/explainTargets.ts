import isFinite from "lodash/isFinite";
import { ExplainKind, ExplainTarget } from "@/types/assistant-sidebar";
import { Thread } from "@/types/traces";

// Visibility rule mirrors the Traces/Spans builders: show the Explain button
// whenever the row is structurally explainable (it has a project_id). The
// displayed value is not gated — N/A or 0 duration/cost is still explainable.
// Threads have no per-row error, so only duration and cost are explainable
// here. entityId is the thread id; projectId scopes it.
//
// Duration and cost share the same guard/scaffold and both attach
// number_of_messages — only the value field differs — so they're built from a
// single factory (mirrors the Traces/Spans builders) to keep them in lockstep.
const threadTargetBuilder =
  (kind: ExplainKind, base: (row: Thread) => Record<string, unknown>) =>
  (row: Thread): ExplainTarget | null => {
    if (!row.project_id) return null;
    const payload = base(row);
    if (isFinite(row.number_of_messages)) {
      payload.number_of_messages = row.number_of_messages;
    }
    return {
      kind,
      entityId: row.id,
      projectId: row.project_id,
      payload,
    };
  };

export const buildThreadDurationTarget = threadTargetBuilder(
  "thread.duration",
  (row) => ({ duration: row.duration }),
);

export const buildThreadCostTarget = threadTargetBuilder(
  "thread.cost",
  (row) => ({ total_estimated_cost: row.total_estimated_cost }),
);
