/**
 * OQL configuration for trace thread filtering
 *
 * Based on backend's TraceThreadField enum.
 * See: apps/opik-backend/src/main/java/com/comet/opik/api/filter/TraceThreadField.java
 */

import { OQLConfig } from "./OQLConfig";
import { OPERATOR_SETS } from "../constants";
import type { ColumnType } from "../types";

export class ThreadOQLConfig extends OQLConfig {
  get columns(): Record<string, ColumnType> {
    return {
      id: "string",
      first_message: "string",
      last_message: "string",
      number_of_messages: "number",
      duration: "number",
      created_at: "date_time",
      last_updated_at: "date_time",
      start_time: "date_time",
      end_time: "date_time",
      feedback_scores: "feedback_scores_number",
      status: "enum",
      tags: "list",
      annotation_queue_ids: "list",
    };
  }

  get supportedOperators(): Record<string, readonly string[]> {
    return {
      id: OPERATOR_SETS.STRING_OPS,
      first_message: OPERATOR_SETS.LIMITED_STRING_OPS,
      last_message: OPERATOR_SETS.LIMITED_STRING_OPS,
      number_of_messages: OPERATOR_SETS.NUMERIC_OPS,
      duration: OPERATOR_SETS.NUMERIC_OPS,
      created_at: OPERATOR_SETS.DATETIME_OPS,
      last_updated_at: OPERATOR_SETS.DATETIME_OPS,
      start_time: OPERATOR_SETS.DATETIME_OPS,
      end_time: OPERATOR_SETS.DATETIME_OPS,
      feedback_scores: OPERATOR_SETS.FEEDBACK_SCORES_OPS,
      status: ["=", "!="] as const,
      tags: OPERATOR_SETS.LIST_OPS,
      annotation_queue_ids: OPERATOR_SETS.LIST_OPS,
    };
  }

  get nestedFields(): readonly string[] {
    return ["feedback_scores"] as const;
  }
}
