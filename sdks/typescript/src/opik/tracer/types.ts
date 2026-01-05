import type { Prompt } from "@/prompt/Prompt";
import type * as OpikApi from "@/rest_api/api";

/**
 * Prompt information dictionary format for serialization.
 * Matches Python SDK format for prompt metadata storage.
 */
export interface PromptInfoDict {
  name: string;
  id?: string;
  version: {
    id?: string;
    commit?: string;
    template: string;
  };
}

/**
 * Extended TraceUpdate type that includes prompts field.
 * Allows associating prompt versions with trace updates.
 */
export interface TraceUpdateData
  extends Omit<OpikApi.TraceUpdate, "projectId"> {
  prompts?: Prompt[];
}

/**
 * Extended SpanUpdate type that includes prompts field.
 * Allows associating prompt versions with span updates.
 */
export interface SpanUpdateData
  extends Omit<
    OpikApi.SpanUpdate,
    "traceId" | "parentSpanId" | "projectId" | "projectName"
  > {
  prompts?: Prompt[];
}
