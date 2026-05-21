/**
 * OQL configuration for prompt version filtering
 *
 * Based on backend's PromptVersionField enum.
 * See: apps/opik-backend/src/main/java/com/comet/opik/api/filter/PromptVersionField.java
 */

import { OQLConfig } from "./OQLConfig";
import { OPERATOR_SETS } from "../constants";
import type { ColumnType } from "../types";

export class PromptVersionOQLConfig extends OQLConfig {
  get columns(): Record<string, ColumnType> {
    return {
      id: "string",
      commit: "string",
      version_number: "string",
      template: "string",
      change_description: "string",
      metadata: "dictionary",
      type: "string",
      tags: "list",
      created_at: "date_time",
      created_by: "string",
    };
  }

  get supportedOperators(): Record<string, readonly string[]> {
    // All string-typed fields here are backend FieldType.STRING_STATE_DB,
    // which does not support > / < — see OPERATOR_SETS.STRING_STATE_DB_OPS.
    return {
      id: OPERATOR_SETS.STRING_STATE_DB_OPS,
      commit: OPERATOR_SETS.STRING_STATE_DB_OPS,
      version_number: OPERATOR_SETS.STRING_STATE_DB_OPS,
      template: OPERATOR_SETS.STRING_STATE_DB_OPS,
      change_description: OPERATOR_SETS.STRING_STATE_DB_OPS,
      metadata: OPERATOR_SETS.DICT_OPS,
      type: ["=", "!="],
      tags: OPERATOR_SETS.LIST_OPS,
      created_at: OPERATOR_SETS.DATETIME_OPS,
      created_by: OPERATOR_SETS.STRING_STATE_DB_OPS,
    };
  }

  get nestedFields(): readonly string[] {
    return ["metadata"] as const;
  }
}
