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
    return {
      id: OPERATOR_SETS.STRING_OPS,
      commit: OPERATOR_SETS.STRING_OPS,
      version_number: OPERATOR_SETS.STRING_OPS,
      template: OPERATOR_SETS.STRING_OPS,
      change_description: OPERATOR_SETS.STRING_OPS,
      metadata: OPERATOR_SETS.DICT_OPS,
      type: ["=", "!="],
      tags: OPERATOR_SETS.LIST_OPS,
      created_at: OPERATOR_SETS.DATETIME_OPS,
      created_by: OPERATOR_SETS.STRING_OPS,
    };
  }

  get nestedFields(): readonly string[] {
    return ["metadata"] as const;
  }
}
