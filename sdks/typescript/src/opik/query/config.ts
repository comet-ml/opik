/**
 * OQL configuration system for entity-specific filtering
 */

import type { ColumnType } from "./types";

/**
 * Configuration for special field handling
 */
export const QUERY_CONFIG = {
  USAGE_KEYS: [
    "total_tokens",
    "prompt_tokens",
    "completion_tokens",
  ] as readonly string[],
  QUOTE_CHARS: ['"', "'"] as const,
  CONNECTORS: {
    ALLOWED: ["and"] as readonly string[],
    FORBIDDEN: ["or"] as readonly string[],
  },
} as const;

export const STRING_OPERATORS = [
  "=",
  "!=",
  "contains",
  "not_contains",
  "starts_with",
  "ends_with",
  ">",
  "<",
] as const;

export const DATE_TIME_OPERATORS = ["=", "!=", ">", ">=", "<", "<="] as const;

export const NUMBER_OPERATORS = ["=", "!=", ">", ">=", "<", "<="] as const;

export const FEEDBACK_SCORES_OPERATORS = [
  "=",
  "!=",
  ">",
  ">=",
  "<",
  "<=",
  "is_empty",
  "is_not_empty",
] as const;

export const LIST_OPERATORS = [
  "=",
  "!=",
  "contains",
  "not_contains",
  "is_empty",
  "is_not_empty",
] as const;

export abstract class OQLConfig {
  abstract get columns(): Record<string, ColumnType>;
  abstract get supportedOperators(): Record<string, readonly string[]>;

  get dictionaryFields(): readonly string[] {
    return ["usage", "feedback_scores", "metadata"];
  }
}

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
      id: STRING_OPERATORS,
      name: STRING_OPERATORS,
      input: STRING_OPERATORS,
      output: STRING_OPERATORS,
      thread_id: STRING_OPERATORS,
      guardrails: STRING_OPERATORS,
      experiment_id: STRING_OPERATORS,
      start_time: DATE_TIME_OPERATORS,
      end_time: DATE_TIME_OPERATORS,
      created_at: DATE_TIME_OPERATORS,
      last_updated_at: DATE_TIME_OPERATORS,
      total_estimated_cost: NUMBER_OPERATORS,
      llm_span_count: NUMBER_OPERATORS,
      "usage.total_tokens": NUMBER_OPERATORS,
      "usage.prompt_tokens": NUMBER_OPERATORS,
      "usage.completion_tokens": NUMBER_OPERATORS,
      duration: NUMBER_OPERATORS,
      input_json: STRING_OPERATORS,
      output_json: STRING_OPERATORS,
      metadata: STRING_OPERATORS,
      feedback_scores: FEEDBACK_SCORES_OPERATORS,
      span_feedback_scores: FEEDBACK_SCORES_OPERATORS,
      tags: LIST_OPERATORS,
      annotation_queue_ids: LIST_OPERATORS,
      error_info: ["is_empty", "is_not_empty"],
      default: STRING_OPERATORS,
    };
  }

  get dictionaryFields(): readonly string[] {
    return [
      "metadata",
      "input_json",
      "output_json",
      "feedback_scores",
      "span_feedback_scores",
    ];
  }
}

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
      id: STRING_OPERATORS,
      name: STRING_OPERATORS,
      input: STRING_OPERATORS,
      output: STRING_OPERATORS,
      model: STRING_OPERATORS,
      provider: STRING_OPERATORS,
      trace_id: STRING_OPERATORS,
      type: ["=", "!="],
      start_time: DATE_TIME_OPERATORS,
      end_time: DATE_TIME_OPERATORS,
      total_estimated_cost: NUMBER_OPERATORS,
      "usage.total_tokens": NUMBER_OPERATORS,
      "usage.prompt_tokens": NUMBER_OPERATORS,
      "usage.completion_tokens": NUMBER_OPERATORS,
      duration: NUMBER_OPERATORS,
      input_json: STRING_OPERATORS,
      output_json: STRING_OPERATORS,
      metadata: STRING_OPERATORS,
      feedback_scores: FEEDBACK_SCORES_OPERATORS,
      tags: LIST_OPERATORS,
      error_info: ["is_empty", "is_not_empty"],
      default: STRING_OPERATORS,
    };
  }

  get dictionaryFields(): readonly string[] {
    return ["metadata", "input_json", "output_json", "feedback_scores"];
  }
}

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
      id: STRING_OPERATORS,
      first_message: STRING_OPERATORS,
      last_message: STRING_OPERATORS,
      number_of_messages: NUMBER_OPERATORS,
      duration: NUMBER_OPERATORS,
      created_at: DATE_TIME_OPERATORS,
      last_updated_at: DATE_TIME_OPERATORS,
      start_time: DATE_TIME_OPERATORS,
      end_time: DATE_TIME_OPERATORS,
      feedback_scores: FEEDBACK_SCORES_OPERATORS,
      status: ["=", "!="],
      tags: LIST_OPERATORS,
      annotation_queue_ids: LIST_OPERATORS,
      default: STRING_OPERATORS,
    };
  }

  get dictionaryFields(): readonly string[] {
    return ["feedback_scores"];
  }
}

export class DatasetItemOQLConfig extends OQLConfig {
  get columns(): Record<string, ColumnType> {
    return {
      id: "string",
      data: "map",
      full_data: "string",
      source: "string",
      trace_id: "string",
      span_id: "string",
      tags: "list",
      created_at: "date_time",
      last_updated_at: "date_time",
      created_by: "string",
      last_updated_by: "string",
    };
  }

  get supportedOperators(): Record<string, readonly string[]> {
    return {
      id: STRING_OPERATORS,
      full_data: STRING_OPERATORS,
      source: STRING_OPERATORS,
      trace_id: STRING_OPERATORS,
      span_id: STRING_OPERATORS,
      created_by: STRING_OPERATORS,
      last_updated_by: STRING_OPERATORS,
      data: ["=", "!=", "contains", "not_contains", "starts_with", "ends_with"],
      tags: LIST_OPERATORS,
      created_at: DATE_TIME_OPERATORS,
      last_updated_at: DATE_TIME_OPERATORS,
      default: STRING_OPERATORS,
    };
  }

  get dictionaryFields(): readonly string[] {
    return ["data"];
  }
}

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
      id: STRING_OPERATORS,
      name: STRING_OPERATORS,
      description: STRING_OPERATORS,
      created_by: STRING_OPERATORS,
      last_updated_by: STRING_OPERATORS,
      template_structure: ["=", "!="],
      created_at: DATE_TIME_OPERATORS,
      last_updated_at: DATE_TIME_OPERATORS,
      version_count: NUMBER_OPERATORS,
      tags: ["contains"],
      default: STRING_OPERATORS,
    };
  }

  get dictionaryFields(): readonly string[] {
    return [];
  }
}

export const OPERATORS_WITHOUT_VALUES = new Set([
  "is_empty",
  "is_not_empty",
]);
