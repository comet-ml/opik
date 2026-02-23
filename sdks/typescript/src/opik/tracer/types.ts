import type { Prompt } from "@/prompt/Prompt";
import type * as OpikApi from "@/rest_api/api";
import type { FeedbackScoreBatchItem } from "@/rest_api/api/types/FeedbackScoreBatchItem";

/**
 * A feedback score for batch operations.
 *
 * Derived from API's FeedbackScoreBatchItem, excluding internal fields (source, projectId, author)
 * that are managed by the SDK. Used with `logTracesFeedbackScores` and `logSpansFeedbackScores`.
 * Matches Python SDK's `BatchFeedbackScoreDict`.
 *
 * @property id - The trace or span ID to attach the score to
 * @property name - The name of the feedback metric (e.g., "accuracy", "helpfulness")
 * @property value - The numerical score value
 * @property categoryName - Optional category for the score
 * @property reason - Optional explanation for the score
 * @property projectName - Optional project name (defaults to client's project)
 */
export type FeedbackScoreData = Omit<
  FeedbackScoreBatchItem,
  "source" | "projectId" | "author"
>;

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
