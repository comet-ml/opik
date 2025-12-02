import {
  COLUMN_CUSTOM_ID,
  COLUMN_FEEDBACK_SCORES_ID,
  COLUMN_ID_ID,
  COLUMN_METADATA_ID,
  COLUMN_TYPE,
  ColumnData,
} from "@/types/shared";
import { BaseTraceData, Thread, Span } from "@/types/traces";

export const TRACE_FILTER_COLUMNS: ColumnData<BaseTraceData>[] = [
  { id: COLUMN_ID_ID, label: "ID", type: COLUMN_TYPE.string },
  { id: "name", label: "Name", type: COLUMN_TYPE.string },
  { id: "start_time", label: "Start time", type: COLUMN_TYPE.time },
  { id: "end_time", label: "End time", type: COLUMN_TYPE.time },
  { id: "input", label: "Input", type: COLUMN_TYPE.string },
  { id: "output", label: "Output", type: COLUMN_TYPE.string },
  { id: "duration", label: "Duration", type: COLUMN_TYPE.duration },
  {
    id: COLUMN_METADATA_ID,
    label: "Metadata",
    type: COLUMN_TYPE.dictionary,
  },
  { id: "tags", label: "Tags", type: COLUMN_TYPE.list, iconType: "tags" },
  { id: "thread_id", label: "Thread ID", type: COLUMN_TYPE.string },
  { id: "error_info", label: "Errors", type: COLUMN_TYPE.errors },
  {
    id: COLUMN_FEEDBACK_SCORES_ID,
    label: "Feedback scores",
    type: COLUMN_TYPE.numberDictionary,
  },
  {
    id: COLUMN_CUSTOM_ID,
    label: "Custom filter",
    type: COLUMN_TYPE.dictionary,
  },
];

export const THREAD_FILTER_COLUMNS: ColumnData<Thread>[] = [
  { id: COLUMN_ID_ID, label: "ID", type: COLUMN_TYPE.string },
  {
    id: "first_message",
    label: "First message",
    type: COLUMN_TYPE.string,
  },
  {
    id: "last_message",
    label: "Last message",
    type: COLUMN_TYPE.string,
  },
  {
    id: "number_of_messages",
    label: "Message count",
    type: COLUMN_TYPE.number,
  },
  {
    id: "status",
    label: "Status",
    type: COLUMN_TYPE.category,
  },
  {
    id: "created_at",
    label: "Created at",
    type: COLUMN_TYPE.time,
  },
  {
    id: "last_updated_at",
    label: "Last updated",
    type: COLUMN_TYPE.time,
  },
  {
    id: "duration",
    label: "Duration",
    type: COLUMN_TYPE.duration,
  },
  { id: "tags", label: "Tags", type: COLUMN_TYPE.list, iconType: "tags" },
  {
    id: "start_time",
    label: "Start time",
    type: COLUMN_TYPE.time,
  },
  {
    id: "end_time",
    label: "End time",
    type: COLUMN_TYPE.time,
  },
  {
    id: COLUMN_FEEDBACK_SCORES_ID,
    label: "Feedback scores",
    type: COLUMN_TYPE.numberDictionary,
  },
];

export const SPAN_FILTER_COLUMNS: ColumnData<Span>[] = [
  { id: COLUMN_ID_ID, label: "ID", type: COLUMN_TYPE.string },
  { id: "name", label: "Name", type: COLUMN_TYPE.string },
  { id: "start_time", label: "Start time", type: COLUMN_TYPE.time },
  { id: "end_time", label: "End time", type: COLUMN_TYPE.time },
  { id: "type", label: "Type", type: COLUMN_TYPE.category },
  { id: "input", label: "Input", type: COLUMN_TYPE.string },
  { id: "output", label: "Output", type: COLUMN_TYPE.string },
  { id: "duration", label: "Duration", type: COLUMN_TYPE.duration },
  {
    id: COLUMN_METADATA_ID,
    label: "Metadata",
    type: COLUMN_TYPE.dictionary,
  },
  { id: "tags", label: "Tags", type: COLUMN_TYPE.list, iconType: "tags" },
  { id: "error_info", label: "Errors", type: COLUMN_TYPE.errors },
  {
    id: COLUMN_FEEDBACK_SCORES_ID,
    label: "Feedback scores",
    type: COLUMN_TYPE.numberDictionary,
  },
  {
    id: COLUMN_CUSTOM_ID,
    label: "Custom filter",
    type: COLUMN_TYPE.dictionary,
  },
];
