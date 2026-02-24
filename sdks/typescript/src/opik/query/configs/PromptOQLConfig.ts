/**
 * OQL configuration for prompt filtering
 *
 * Based on backend's PromptField enum.
 * See: apps/opik-backend/src/main/java/com/comet/opik/api/filter/PromptField.java
 */

import { OQLConfig } from "./OQLConfig";
import { OPERATOR_SETS } from "../constants";
import type { ColumnType } from "../types";

export class PromptOQLConfig extends OQLConfig {
  get columns(): Record<string, ColumnType> {
    return {
      id: "string",
      name: "string",
      description: "string",
      created_at: "date_time",
      last_updated_at: "date_time",
      created_by: "string",
      last_updated_by: "string",
      tags: "list",
      version_count: "number",
      template_structure: "string",
    };
  }

  get supportedOperators(): Record<string, readonly string[]> {
    return {
      id: OPERATOR_SETS.STRING_OPS,
      name: OPERATOR_SETS.STRING_OPS,
      description: OPERATOR_SETS.STRING_OPS,
      created_at: OPERATOR_SETS.DATETIME_OPS,
      last_updated_at: OPERATOR_SETS.DATETIME_OPS,
      created_by: OPERATOR_SETS.STRING_OPS,
      last_updated_by: OPERATOR_SETS.STRING_OPS,
      tags: OPERATOR_SETS.LIST_OPS,
      version_count: OPERATOR_SETS.NUMERIC_OPS,
      template_structure: OPERATOR_SETS.STRING_OPS,
    };
  }

  get nestedFields(): readonly string[] {
    return [] as const;
  }
}
