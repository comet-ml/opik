/**
 * Abstract base class for OQL (Opik Query Language) configuration
 */

import type { ColumnType } from "../types";

export abstract class OQLConfig {
  /**
   * Map of supported fields to their types
   */
  abstract get columns(): Record<string, ColumnType>;

  /**
   * Map of fields to their supported operators
   */
  abstract get supportedOperators(): Record<string, readonly string[]>;

  /**
   * Fields that support nested key access via dot notation
   */
  get nestedFields(): readonly string[] {
    return ["usage", "metadata", "feedback_scores"] as const;
  }

  /**
   * Keys supported for the usage field
   */
  get usageKeys(): readonly string[] {
    return ["total_tokens", "prompt_tokens", "completion_tokens"] as const;
  }
}
