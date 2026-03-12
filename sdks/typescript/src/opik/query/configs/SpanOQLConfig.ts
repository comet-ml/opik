/**
 * OQL configuration for span filtering
 *
 * Based on backend's SpanField enum.
 * See: apps/opik-backend/src/main/java/com/comet/opik/api/filter/SpanField.java
 */

import { OQLConfig } from "./OQLConfig";
import { OPERATOR_SETS } from "../constants";
import type { ColumnType } from "../types";

export class SpanOQLConfig extends OQLConfig {
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
      model: "string",
      provider: "string",
      total_estimated_cost: "number",
      tags: "list",
      "usage.total_tokens": "number",
      "usage.prompt_tokens": "number",
      "usage.completion_tokens": "number",
      feedback_scores: "feedback_scores_number",
      duration: "number",
      error_info: "error_container",
      type: "enum",
      trace_id: "string",
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
      tags: OPERATOR_SETS.LIST_OPS,
      "usage.total_tokens": OPERATOR_SETS.NUMERIC_OPS,
      "usage.prompt_tokens": OPERATOR_SETS.NUMERIC_OPS,
      "usage.completion_tokens": OPERATOR_SETS.NUMERIC_OPS,
      duration: OPERATOR_SETS.NUMERIC_OPS,
      total_estimated_cost: OPERATOR_SETS.NUMERIC_OPS,
      model: OPERATOR_SETS.STRING_OPS,
      provider: OPERATOR_SETS.STRING_OPS,
      error_info: ["is_empty", "is_not_empty"],
      type: ["=", "!="],
      trace_id: OPERATOR_SETS.STRING_OPS,
    };
  }

  get nestedFields(): readonly string[] {
    return [
      "usage",
      "metadata",
      "feedback_scores",
      "input_json",
      "output_json",
    ] as const;
  }
}
