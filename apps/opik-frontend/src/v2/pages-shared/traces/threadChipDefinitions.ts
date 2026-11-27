import compact from "lodash/compact";
import keyBy from "lodash/keyBy";

import { COLUMN_FEEDBACK_SCORES_ID, COLUMN_TYPE } from "@/types/shared";
import {
  ChipDefinition,
  ChipOptionsResult,
  chipOptions,
  chipOptionsValue,
} from "@/shared/filter-chips/types";
import {
  TAGS_OPERATORS,
  FEEDBACK_SCORE_OPERATORS,
  STRING_OPERATORS,
  LIST_OPERATORS,
  COMMENTS_OPERATORS,
} from "@/shared/filter-chips/chips/QueryBuilderChip/operators";
import { useTagsOptions } from "@/v2/pages-shared/TagsAutocomplete/useTagsOptions";

/**
 * Filter-chip definitions for threads, shared by the Logs page and by annotation queue automation so
 * both filter threads through one definition.
 */

export const THREAD_CHIP_DEFINITIONS_STATIC: ChipDefinition[] = [
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
    id: "number_of_messages",
    field: "number_of_messages",
    label: "Message count",
    kind: "numeric",
    columnType: COLUMN_TYPE.number,
    format: "integer",
  },
  {
    id: "first_message",
    field: "first_message",
    label: "First message",
    kind: "query-builder",
    columnType: COLUMN_TYPE.string,
    operators: STRING_OPERATORS,
    defaultOperator: "contains",
    value: { placeholder: "Search first message" },
  },
  {
    id: "last_message",
    field: "last_message",
    label: "Last message",
    kind: "query-builder",
    columnType: COLUMN_TYPE.string,
    operators: STRING_OPERATORS,
    defaultOperator: "contains",
    value: { placeholder: "Search last message" },
  },
  {
    id: "id",
    field: "id",
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
  {
    id: "comments",
    field: "comments",
    label: "Comments",
    addLabel: "Add comment",
    kind: "query-builder",
    columnType: COLUMN_TYPE.list,
    operators: COMMENTS_OPERATORS,
    defaultOperator: "is_not_empty",
  },
];

export const THREAD_CHIP_ORDER: string[] = [
  "start_time",
  "end_time",
  "duration",
  "number_of_messages",
  "first_message",
  "last_message",
  "tags",
  "id",
  "annotation_queue_ids",
  "comments",
  "feedback_scores",
];

export const buildThreadChipDefinitions = ({
  projectId,
  threadScoreOptions,
}: {
  projectId: string;
  threadScoreOptions: ChipOptionsResult;
}): ChipDefinition[] => {
  const dynamicChips: Record<string, ChipDefinition> = {
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
          entityType: "threads",
        }),
      },
      addLabel: "Add tag",
    },
    feedback_scores: {
      id: "feedback_scores",
      field: COLUMN_FEEDBACK_SCORES_ID,
      label: "Feedback scores",
      addLabel: "Add feedback score",
      kind: "query-builder",
      columnType: COLUMN_TYPE.numberDictionary,
      operators: FEEDBACK_SCORE_OPERATORS,
      defaultOperator: ">=",
      key: {
        placeholder: "Select score",
        options: chipOptionsValue(threadScoreOptions),
      },
      value: { type: "numeric", decimals: 2, placeholder: "0" },
    },
  };
  const byId: Record<string, ChipDefinition> = {
    ...keyBy(THREAD_CHIP_DEFINITIONS_STATIC, "id"),
    ...dynamicChips,
  };
  return compact(THREAD_CHIP_ORDER.map((id) => byId[id]));
};
