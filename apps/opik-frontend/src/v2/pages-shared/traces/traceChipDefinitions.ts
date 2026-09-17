import compact from "lodash/compact";
import keyBy from "lodash/keyBy";

import {
  COLUMN_CUSTOM_ID,
  COLUMN_FEEDBACK_SCORES_ID,
  COLUMN_METADATA_ID,
  COLUMN_SPAN_FEEDBACK_SCORES_ID,
  COLUMN_TYPE,
} from "@/types/shared";
import { LOGS_SOURCE } from "@/types/traces";
import { GuardrailResult } from "@/types/guardrails";
import { TRACE_DATA_TYPE } from "@/constants/traces";
import { CUSTOM_FILTER_VALIDATION_REGEXP } from "@/constants/filters";
import {
  ChipDefinition,
  ChipOptionsResult,
  chipOptions,
  chipOptionsValue,
} from "@/shared/filter-chips/types";
import {
  TAGS_OPERATORS,
  FEEDBACK_SCORE_OPERATORS,
  DICTIONARY_OPERATORS,
  STRING_OPERATORS,
  LIST_OPERATORS,
} from "@/shared/filter-chips/chips/QueryBuilderChip/operators";
import { useTagsOptions } from "@/v2/pages-shared/TagsAutocomplete/useTagsOptions";
import { usePathsOptions } from "@/v2/pages-shared/traces/TracesOrSpansPathsAutocomplete/usePathsOptions";
import { useErrorTypeOptions } from "@/v2/pages-shared/traces/ErrorTypeAutocomplete/useErrorTypeOptions";

/**
 * Filter-chip definitions for traces, shared by the Logs page and by the entity-scoped trace logs
 * view (experiment Logs tab, playground, trials, annotation queues, evaluator traces) so both
 * surfaces filter through one definition.
 *
 * This lives under pages-shared rather than shared/filter-chips because the option sources
 * (tags, metadata paths, error types) are pages-shared hooks, and shared/ may not import them.
 */

export const TRACE_CHIP_DEFINITIONS_STATIC: ChipDefinition[] = [
  {
    id: "start_time",
    field: "start_time",
    label: "Start time",
    kind: "time",
    columnType: COLUMN_TYPE.time,
  },
  {
    id: "end_time",
    field: "end_time",
    label: "End time",
    kind: "time",
    columnType: COLUMN_TYPE.time,
  },
  {
    id: "duration",
    field: "duration",
    label: "Duration",
    kind: "numeric",
    columnType: COLUMN_TYPE.duration,
    format: "duration",
  },
  {
    id: "total_estimated_cost",
    field: "total_estimated_cost",
    label: "Cost",
    kind: "numeric",
    columnType: COLUMN_TYPE.cost,
    format: "currency",
  },
  {
    id: "usage_total_tokens",
    field: "usage.total_tokens",
    label: "Tokens",
    kind: "numeric",
    columnType: COLUMN_TYPE.number,
    format: "integer",
  },
  {
    id: "usage_prompt_tokens",
    field: "usage.prompt_tokens",
    label: "Input tokens",
    kind: "numeric",
    columnType: COLUMN_TYPE.number,
    format: "integer",
  },
  {
    id: "usage_completion_tokens",
    field: "usage.completion_tokens",
    label: "Output tokens",
    kind: "numeric",
    columnType: COLUMN_TYPE.number,
    format: "integer",
  },
  {
    id: "llm_span_count",
    field: "llm_span_count",
    label: "LLM calls count",
    kind: "numeric",
    columnType: COLUMN_TYPE.number,
    format: "integer",
  },
  {
    id: "input",
    field: "input",
    label: "Input",
    kind: "query-builder",
    columnType: COLUMN_TYPE.string,
    operators: STRING_OPERATORS,
    defaultOperator: "contains",
    value: { placeholder: "Search input" },
  },
  {
    id: "output",
    field: "output",
    label: "Output",
    kind: "query-builder",
    columnType: COLUMN_TYPE.string,
    operators: STRING_OPERATORS,
    defaultOperator: "contains",
    value: { placeholder: "Search output" },
  },
  {
    id: "name",
    field: "name",
    label: "Trace name",
    kind: "query-builder",
    columnType: COLUMN_TYPE.string,
    operators: STRING_OPERATORS,
    defaultOperator: "contains",
    value: { placeholder: "Search name" },
  },
  {
    id: "with_errors",
    field: "error_info",
    label: "With errors",
    kind: "boolean",
    onOperator: "is_not_empty",
    columnType: COLUMN_TYPE.errors,
  },
  {
    id: "id",
    field: "id",
    label: "Trace ID",
    kind: "query-builder",
    columnType: COLUMN_TYPE.string,
    operators: STRING_OPERATORS,
    defaultOperator: "contains",
    value: { placeholder: "Enter trace ID" },
  },
  {
    id: "thread_id",
    field: "thread_id",
    label: "Thread ID",
    kind: "query-builder",
    columnType: COLUMN_TYPE.string,
    operators: STRING_OPERATORS,
    defaultOperator: "contains",
    value: { placeholder: "Enter thread ID" },
  },
  {
    id: "annotation_queue_ids",
    field: "annotation_queue_ids",
    label: "Annotation queue ID",
    kind: "query-builder",
    columnType: COLUMN_TYPE.list,
    operators: LIST_OPERATORS,
    defaultOperator: "contains",
    value: { placeholder: "Enter annotation queue ID" },
  },
];

export const TRACE_CHIP_ORDER: string[] = [
  "start_time",
  "end_time",
  "duration",
  "total_estimated_cost",
  "usage_total_tokens",
  "usage_prompt_tokens",
  "usage_completion_tokens",
  "llm_span_count",
  "input",
  "output",
  "name",
  "with_errors",
  "error_type",
  "tags",
  "id",
  "thread_id",
  "annotation_queue_ids",
  "feedback_scores",
  "span_feedback_scores",
  "guardrails",
  "metadata",
  "custom",
];

export const TRACE_DEFAULT_PINNED_CHIPS = ["with_errors", "tags", "metadata"];
export const SPAN_DEFAULT_PINNED_CHIPS = [
  "type",
  "tags",
  "with_errors",
  "metadata",
];

export const buildSharedDynamicChips = ({
  projectId,
  type,
  scoreOptions,
  feedbackScoresLabel,
  isGuardrailsEnabled,
  logsSource,
}: {
  projectId: string;
  type: TRACE_DATA_TYPE;
  scoreOptions: ChipOptionsResult;
  feedbackScoresLabel: string;
  isGuardrailsEnabled: boolean;
  // Required, though it may be undefined: a default would silently scope a new surface's tag,
  // error-type and metadata-path options to the SDK source, which is the footgun this parameter
  // exists to remove. Undefined means "no source filter".
  logsSource: LOGS_SOURCE | undefined;
}): Record<string, ChipDefinition> => {
  const entityType: "spans" | "traces" =
    type === TRACE_DATA_TYPE.spans ? "spans" : "traces";
  const chips: Record<string, ChipDefinition> = {
    tags: {
      id: "tags",
      field: "tags",
      label: "Tags",
      kind: "query-builder",
      columnType: COLUMN_TYPE.list,
      operators: TAGS_OPERATORS,
      defaultOperator: "contains",
      value: {
        placeholder: "Type a tag…",
        options: chipOptions(useTagsOptions, {
          projectId,
          entityType,
          logsSource,
        }),
      },
      addLabel: "Add tag",
    },
    error_type: {
      id: "error_type",
      field: "error_type",
      label: "Error type",
      kind: "query-builder",
      columnType: COLUMN_TYPE.string,
      operators: ["contains", "=", "not_contains", "starts_with", "ends_with"],
      defaultOperator: "contains",
      value: {
        placeholder: "Select error type",
        options: chipOptions(useErrorTypeOptions, {
          projectId,
          type,
          logsSource,
        }),
      },
    },
    feedback_scores: {
      id: "feedback_scores",
      field: COLUMN_FEEDBACK_SCORES_ID,
      label: feedbackScoresLabel,
      kind: "query-builder",
      columnType: COLUMN_TYPE.numberDictionary,
      operators: FEEDBACK_SCORE_OPERATORS,
      defaultOperator: ">=",
      key: {
        placeholder: "Select score",
        options: chipOptionsValue(scoreOptions),
      },
      value: { type: "numeric", decimals: 2, placeholder: "0" },
    },
    metadata: {
      id: "metadata",
      field: COLUMN_METADATA_ID,
      label: "Metadata",
      kind: "query-builder",
      columnType: COLUMN_TYPE.dictionary,
      operators: DICTIONARY_OPERATORS,
      defaultOperator: "contains",
      key: {
        placeholder: "key",
        options: chipOptions(usePathsOptions, {
          projectId,
          type,
          rootKeys: ["metadata"],
          excludeRoot: true,
          logsSource,
        }),
      },
      value: { placeholder: "value" },
    },
    custom: {
      id: "custom",
      field: COLUMN_CUSTOM_ID,
      label: "Custom filter",
      kind: "query-builder",
      columnType: COLUMN_TYPE.dictionary,
      operators: DICTIONARY_OPERATORS,
      defaultOperator: "contains",
      key: {
        placeholder: "key",
        options: chipOptions(usePathsOptions, {
          projectId,
          type,
          rootKeys: ["input", "output"],
          excludeRoot: false,
          logsSource,
        }),
        validate: (k) =>
          CUSTOM_FILTER_VALIDATION_REGEXP.test(k)
            ? undefined
            : 'Key must begin with "input" or "output" (e.g. "input.message")',
      },
      value: { placeholder: "value" },
    },
  };
  if (isGuardrailsEnabled) {
    chips.guardrails = {
      id: "guardrails",
      field: "guardrails",
      label: "Guardrails",
      kind: "single-select",
      options: [
        { value: GuardrailResult.FAILED, label: "Failed" },
        { value: GuardrailResult.PASSED, label: "Passed" },
      ],
      columnType: COLUMN_TYPE.category,
      operator: "=",
    };
  }
  return chips;
};

/**
 * Composes the ordered trace chip list: the static definitions plus the dynamic ones whose options
 * come from the project (tags, error types, metadata paths, feedback score names).
 */
export const buildTraceChipDefinitions = ({
  projectId,
  traceScoreOptions,
  spanScoreOptions,
  isGuardrailsEnabled,
  logsSource,
}: {
  projectId: string;
  traceScoreOptions: ChipOptionsResult;
  spanScoreOptions: ChipOptionsResult;
  isGuardrailsEnabled: boolean;
  logsSource: LOGS_SOURCE | undefined;
}): ChipDefinition[] => {
  const dynamicChips: Record<string, ChipDefinition> = {
    ...buildSharedDynamicChips({
      projectId,
      type: TRACE_DATA_TYPE.traces,
      scoreOptions: traceScoreOptions,
      feedbackScoresLabel: "Trace feedback scores",
      isGuardrailsEnabled,
      logsSource,
    }),
    span_feedback_scores: {
      id: "span_feedback_scores",
      field: COLUMN_SPAN_FEEDBACK_SCORES_ID,
      label: "Span feedback scores",
      kind: "query-builder",
      columnType: COLUMN_TYPE.numberDictionary,
      operators: FEEDBACK_SCORE_OPERATORS,
      defaultOperator: ">=",
      key: {
        placeholder: "Select span score",
        options: chipOptionsValue(spanScoreOptions),
      },
      value: { type: "numeric", decimals: 2, placeholder: "0" },
    },
  };

  const byId: Record<string, ChipDefinition> = {
    ...keyBy(TRACE_CHIP_DEFINITIONS_STATIC, "id"),
    ...dynamicChips,
  };

  return compact(TRACE_CHIP_ORDER.map((id) => byId[id]));
};
