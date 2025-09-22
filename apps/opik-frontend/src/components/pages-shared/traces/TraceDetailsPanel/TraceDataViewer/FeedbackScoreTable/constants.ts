import { COLUMN_TYPE, ColumnData } from "@/types/shared";
import { ExpandingFeedbackScoreRow } from "./types";
import SourceCell from "./cells/SourceCell";
import NameCell from "./cells/NameCell";
import ValueCell from "./cells/ValueCell";
import ReasonCell from "./cells/ReasonCell";
import AuthorCell from "./cells/AuthorCell";

export enum FeedbackScoreTableColumns {
  SOURCE = "source",
  KEY = "key",
  VALUE = "value",
  REASON = "reason",
  CREATED_BY = "created_by",
}

export const DEFAULT_SELECTED_COLUMNS = [
  FeedbackScoreTableColumns.VALUE,
  FeedbackScoreTableColumns.REASON,
];

export enum FEEDBACK_SCORE_ROW_TYPE {
  SINGLE = "single",
  PARENT = "parent",
  CHILD = "child",
}

export const PARENT_ROW_ID_PREFIX = "feedback-score-parent-";

export const SELECTED_COLUMNS_KEY = "feedback-scores-tab-selected-columns";
export const COLUMNS_ORDER_KEY = "feedback-scores-tab-columns-order";
export const COLUMN_SIZING_KEY = "feedback-scores-tab-column-sizing";

export type EntityTypeStorageValues = {
  selectedColumns: string;
  columnsOrder: string;
  columnSizing: string;
};

// Map entity types to localStorage keys - trace and span share the same keys
export const ENTITY_TYPE_TO_STORAGE_KEYS: Record<
  string,
  EntityTypeStorageValues
> = {
  // trace and span share the same localStorage keys
  trace: {
    selectedColumns: `trace-${SELECTED_COLUMNS_KEY}`,
    columnsOrder: `trace-${COLUMNS_ORDER_KEY}`,
    columnSizing: `trace-${COLUMN_SIZING_KEY}`,
  },
  span: {
    selectedColumns: `trace-${SELECTED_COLUMNS_KEY}`,
    columnsOrder: `trace-${COLUMNS_ORDER_KEY}`,
    columnSizing: `trace-${COLUMN_SIZING_KEY}`,
  },
  // thread and experiment have their own keys
  thread: {
    selectedColumns: `thread-${SELECTED_COLUMNS_KEY}`,
    columnsOrder: `thread-${COLUMNS_ORDER_KEY}`,
    columnSizing: `thread-${COLUMN_SIZING_KEY}`,
  },
  experiment: {
    selectedColumns: `experiment-${SELECTED_COLUMNS_KEY}`,
    columnsOrder: `experiment-${COLUMNS_ORDER_KEY}`,
    columnSizing: `experiment-${COLUMN_SIZING_KEY}`,
  },
};

export const NON_CONFIGURABLE_COLUMNS: ColumnData<ExpandingFeedbackScoreRow>[] =
  [
    {
      id: FeedbackScoreTableColumns.KEY,
      label: "Key",
      type: COLUMN_TYPE.string,
      size: 100,
      cell: NameCell as never,
    },
  ];

export const CONFIGURABLE_COLUMNS: ColumnData<ExpandingFeedbackScoreRow>[] = [
  {
    id: FeedbackScoreTableColumns.SOURCE,
    label: "Source",
    type: COLUMN_TYPE.string,
    size: 100,
    cell: SourceCell as never,
  },
  {
    id: FeedbackScoreTableColumns.VALUE,
    label: "Score",
    type: COLUMN_TYPE.string,
    cell: ValueCell as never,
    size: 100,
  },
  {
    id: FeedbackScoreTableColumns.REASON,
    label: "Reason",
    type: COLUMN_TYPE.string,
    cell: ReasonCell as never,
    size: 100,
  },
  {
    id: FeedbackScoreTableColumns.CREATED_BY,
    label: "Scored by",
    type: COLUMN_TYPE.string,
    cell: AuthorCell as never,
    size: 100,
  },
];
