import isObject from "lodash/isObject";

import CodeCell from "@/components/shared/DataTableCells/CodeCell";
import ListCell from "@/components/shared/DataTableCells/ListCell";
import IdCell from "@/components/shared/DataTableCells/IdCell";
import FeedbackScoresCell from "@/components/shared/DataTableCells/FeedbackScoresCell";
import { formatDate } from "@/lib/date";
import { COLUMN_TYPE, ColumnData } from "@/types/shared";
import { BASE_TRACE_DATA_TYPE, BaseTraceData, SPAN_TYPE } from "@/types/traces";

export const TRACES_PAGE_COLUMNS: ColumnData<BaseTraceData>[] = [
  {
    id: "id",
    label: "ID",
    type: COLUMN_TYPE.string,
    cell: IdCell as never,
  },
  {
    id: "name",
    label: "Name",
    type: COLUMN_TYPE.string,
  },
  {
    id: "start_time",
    label: "Start time",
    type: COLUMN_TYPE.time,
    accessorFn: (row) => formatDate(row.start_time),
  },
  {
    id: "end_time",
    label: "End time",
    type: COLUMN_TYPE.time,
    accessorFn: (row) => formatDate(row.end_time),
  },
  {
    id: "input",
    label: "Input",
    size: 400,
    type: COLUMN_TYPE.string,
    iconType: COLUMN_TYPE.dictionary,
    accessorFn: (row) =>
      isObject(row.input) ? JSON.stringify(row.input, null, 2) : row.input,
    cell: CodeCell as never,
  },
  {
    id: "output",
    label: "Output",
    size: 400,
    type: COLUMN_TYPE.string,
    iconType: COLUMN_TYPE.dictionary,
    accessorFn: (row) =>
      isObject(row.output) ? JSON.stringify(row.output, null, 2) : row.output,
    cell: CodeCell as never,
  },
  {
    id: "metadata",
    label: "Metadata",
    type: COLUMN_TYPE.dictionary,
    accessorFn: (row) =>
      isObject(row.metadata)
        ? JSON.stringify(row.metadata, null, 2)
        : row.metadata,
    cell: CodeCell as never,
  },
  {
    id: "feedback_scores",
    label: "Feedback scores",
    size: 300,
    type: COLUMN_TYPE.numberDictionary,
    cell: FeedbackScoresCell as never,
  },
  {
    id: "tags",
    label: "Tags",
    type: COLUMN_TYPE.list,
    cell: ListCell as never,
  },
  {
    id: "usage.total_tokens",
    label: "Total tokens",
    type: COLUMN_TYPE.number,
    accessorFn: (row) => (row.usage ? `${row.usage.total_tokens}` : ""),
  },
  {
    id: "usage.prompt_tokens",
    label: "Total input tokens",
    type: COLUMN_TYPE.number,
    accessorFn: (row) => (row.usage ? `${row.usage.prompt_tokens}` : ""),
  },
  {
    id: "usage.completion_tokens",
    label: "Total output tokens",
    type: COLUMN_TYPE.number,
    accessorFn: (row) => (row.usage ? `${row.usage.completion_tokens}` : ""),
  },
];

export const DEFAULT_TRACES_PAGE_COLUMNS: string[] = [
  "name",
  "input",
  "output",
  "feedback_scores",
];

export const TRACE_TYPE_FOR_TREE = "trace";

export const SPANS_COLORS_MAP: Record<BASE_TRACE_DATA_TYPE, string> = {
  [TRACE_TYPE_FOR_TREE]: "#945FCF",
  [SPAN_TYPE.llm]: "#5899DA",
  [SPAN_TYPE.general]: "#19A979",
  [SPAN_TYPE.tool]: "#BF399E",
};
