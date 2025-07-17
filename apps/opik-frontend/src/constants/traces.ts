import { BASE_TRACE_DATA_TYPE, SPAN_TYPE } from "@/types/traces";

export const TRACE_TYPE_FOR_TREE = "trace";

export const METADATA_AGENT_GRAPH_KEY = "_opik_graph_definition";

export const SPANS_COLORS_MAP: Record<BASE_TRACE_DATA_TYPE, string> = {
  [TRACE_TYPE_FOR_TREE]: "#945FCF",
  [SPAN_TYPE.llm]: "#5899DA",
  [SPAN_TYPE.general]: "#19A979",
  [SPAN_TYPE.tool]: "#BF399E",
  [SPAN_TYPE.guardrail]: "#FB9341",
};

export const SPAN_TYPE_LABELS_MAP: Record<SPAN_TYPE, string> = {
  [SPAN_TYPE.llm]: "LLM",
  [SPAN_TYPE.general]: "General",
  [SPAN_TYPE.tool]: "Tool",
  [SPAN_TYPE.guardrail]: "Guardrail",
};
