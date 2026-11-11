import pick from "lodash/pick";
import { Thread, Trace } from "@/types/traces";
import { KitPack } from "@/shared/SandboxedView/kit/loadKit";

export const PREVIEW_SAMPLE_SIZE = 3;

const TRACE_KEYS: (keyof Trace)[] = [
  "id",
  "name",
  "input",
  "output",
  "metadata",
  "tags",
  "feedback_scores",
  "start_time",
  "end_time",
  "duration",
  "usage",
  "total_estimated_cost",
];

const THREAD_KEYS: (keyof Thread)[] = [
  "id",
  "start_time",
  "end_time",
  "duration",
  "number_of_messages",
  "status",
  "tags",
  "feedback_scores",
  "usage",
  "total_estimated_cost",
];

export const ANNOTATOR_VIEW_PACKS: KitPack[] = ["review"];

export const toAnnotatorTraceViewData = (trace: Trace) => ({
  trace: pick(trace, TRACE_KEYS),
});

export const toAnnotatorThreadViewData = (thread: Thread, traces: Trace[]) => ({
  thread: pick(thread, THREAD_KEYS),
  traces: traces.map((trace) => pick(trace, TRACE_KEYS)),
});
