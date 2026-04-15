import { BASE_TRACE_DATA_TYPE, SPAN_TYPE } from "@/types/traces";

export const TRACE_TYPE_FOR_TREE = "trace";

// Extends BASE_TRACE_DATA_TYPE with additional span subtypes used in the tree color map
export type TRACE_COLOR_TYPE =
  | BASE_TRACE_DATA_TYPE
  | "thread"
  | "system"
  | "function"
  | "user"
  | "assistant";

export enum TRACE_DATA_TYPE {
  traces = "traces",
  spans = "spans",
}

export enum LOGS_TYPE {
  threads = "threads",
  traces = "traces",
  spans = "spans",
}

export enum PROJECT_TAB {
  logs = "logs",
  insights = "insights",
  evaluators = "rules",
  annotationQueues = "annotation-queues",
  configuration = "configuration",
}

export const METADATA_AGENT_GRAPH_KEY = "_opik_graph_definition";

export const SPANS_COLORS_MAP: Record<BASE_TRACE_DATA_TYPE, string> = {
  [TRACE_TYPE_FOR_TREE]: "var(--color-purple)",
  [SPAN_TYPE.llm]: "var(--color-blue)",
  [SPAN_TYPE.general]: "var(--color-green)",
  [SPAN_TYPE.tool]: "var(--color-burgundy)",
  [SPAN_TYPE.guardrail]: "var(--color-orange)",
};

export const TRACE_TYPE_COLORS_MAP: Record<
  TRACE_COLOR_TYPE,
  { color: string; bg: string }
> = {
  [TRACE_TYPE_FOR_TREE]: {
    color: "var(--type-trace)",
    bg: "var(--type-trace-bg)",
  },
  thread: { color: "var(--type-thread)", bg: "var(--type-thread-bg)" },
  [SPAN_TYPE.general]: {
    color: "var(--type-span-general)",
    bg: "var(--type-span-general-bg)",
  },
  [SPAN_TYPE.guardrail]: {
    color: "var(--type-span-guardrail)",
    bg: "var(--type-span-guardrail-bg)",
  },
  [SPAN_TYPE.llm]: {
    color: "var(--type-span-llm)",
    bg: "var(--type-span-llm-bg)",
  },
  [SPAN_TYPE.tool]: {
    color: "var(--type-span-tool)",
    bg: "var(--type-span-tool-bg)",
  },
  system: {
    color: "var(--type-span-system)",
    bg: "var(--type-span-system-bg)",
  },
  function: {
    color: "var(--type-span-function)",
    bg: "var(--type-span-function-bg)",
  },
  user: { color: "var(--type-llm-user)", bg: "var(--type-llm-user-bg)" },
  assistant: {
    color: "var(--type-llm-assistant)",
    bg: "var(--type-llm-assistant-bg)",
  },
};

export const SPAN_TYPE_LABELS_MAP: Record<SPAN_TYPE, string> = {
  [SPAN_TYPE.llm]: "LLM call",
  [SPAN_TYPE.general]: "General",
  [SPAN_TYPE.tool]: "Tool",
  [SPAN_TYPE.guardrail]: "Guardrail",
};
