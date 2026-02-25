/**
 * OQL configuration for trace filtering
 *
 * Based on backend's TraceField enum.
 * See: apps/opik-backend/src/main/java/com/comet/opik/api/filter/TraceField.java
 */

import { OQLConfig } from "./OQLConfig";
import { OPERATOR_SETS } from "../constants";
import type { ColumnType } from "../types";

export class TraceOQLConfig extends OQLConfig {
  get columns(): Record<string, ColumnType> {
    return {
      id: "string",
      name: "string",
      start_time: "date_time",
      end_time: "date_time",
      input: "string",
      output: "string",
      input_json: "dictionary",
      output_json: "dictionary",
      metadata: "dictionary",
      total_estimated_cost: "number",
      llm_span_count: "number",
      tags: "list",
      "usage.total_tokens": "number",
      "usage.prompt_tokens": "number",
      "usage.completion_tokens": "number",
      feedback_scores: "feedback_scores_number",
      span_feedback_scores: "feedback_scores_number",
      duration: "number",
      thread_id: "string",
      guardrails: "string",
      error_info: "error_container",
      created_at: "date_time",
      last_updated_at: "date_time",
      annotation_queue_ids: "list",
      experiment_id: "string",
    };
  }

  get supportedOperators(): Record<string, readonly string[]> {
    return {
      id: OPERATOR_SETS.STRING_OPS,
      name: OPERATOR_SETS.STRING_OPS,
      start_time: OPERATOR_SETS.DATETIME_OPS,
      end_time: OPERATOR_SETS.DATETIME_OPS,
      input: OPERATOR_SETS.LIMITED_STRING_OPS,
      output: OPERATOR_SETS.LIMITED_STRING_OPS,
      input_json: OPERATOR_SETS.DICT_OPS,
      output_json: OPERATOR_SETS.DICT_OPS,
      metadata: OPERATOR_SETS.DICT_OPS,
      feedback_scores: OPERATOR_SETS.FEEDBACK_SCORES_OPS,
      span_feedback_scores: OPERATOR_SETS.FEEDBACK_SCORES_OPS,
      tags: OPERATOR_SETS.LIST_OPS,
      "usage.total_tokens": OPERATOR_SETS.NUMERIC_OPS,
      "usage.prompt_tokens": OPERATOR_SETS.NUMERIC_OPS,
      "usage.completion_tokens": OPERATOR_SETS.NUMERIC_OPS,
      duration: OPERATOR_SETS.NUMERIC_OPS,
      thread_id: OPERATOR_SETS.STRING_OPS,
      total_estimated_cost: OPERATOR_SETS.NUMERIC_OPS,
      llm_span_count: OPERATOR_SETS.NUMERIC_OPS,
      guardrails: OPERATOR_SETS.STRING_OPS,
      error_info: ["is_empty", "is_not_empty"],
      created_at: OPERATOR_SETS.DATETIME_OPS,
      last_updated_at: OPERATOR_SETS.DATETIME_OPS,
      annotation_queue_ids: OPERATOR_SETS.LIST_OPS,
      experiment_id: OPERATOR_SETS.STRING_OPS,
    };
  }

  get nestedFields(): readonly string[] {
    return [
      "usage",
      "metadata",
      "feedback_scores",
      "span_feedback_scores",
      "input_json",
      "output_json",
    ] as const;
  }
}
