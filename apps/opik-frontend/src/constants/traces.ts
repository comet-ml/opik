import { BASE_TRACE_DATA_TYPE, SPAN_TYPE } from "@/types/traces";

export const TRACE_TYPE_FOR_TREE = "trace";

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
  metrics = "metrics",
  evaluators = "rules",
  annotationQueues = "annotation-queues",
  execution = "execution",
}

export const METADATA_AGENT_GRAPH_KEY = "_opik_graph_definition";

export const SPANS_COLORS_MAP: Record<BASE_TRACE_DATA_TYPE, string> = {
  [TRACE_TYPE_FOR_TREE]: "var(--color-purple)",
  [SPAN_TYPE.llm]: "var(--color-blue)",
  [SPAN_TYPE.general]: "var(--color-green)",
  [SPAN_TYPE.tool]: "var(--color-burgundy)",
  [SPAN_TYPE.guardrail]: "var(--color-orange)",
};

export const SPAN_TYPE_LABELS_MAP: Record<SPAN_TYPE, string> = {
  [SPAN_TYPE.llm]: "LLM call",
  [SPAN_TYPE.general]: "General",
  [SPAN_TYPE.tool]: "Tool",
  [SPAN_TYPE.guardrail]: "Guardrail",
};
