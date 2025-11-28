import { COLUMN_TYPE, ColumnData } from "@/types/shared";
import { ExpandingFeedbackScoreRow } from "./types";
import SourceCell from "./cells/SourceCell";
import NameCell from "./cells/NameCell";
import ValueCell from "./cells/ValueCell";
import ReasonCell from "./cells/ReasonCell";
import AuthorCell from "./cells/AuthorCell";
import TypeCell from "./cells/TypeCell";

export enum FeedbackScoreTableColumns {
  SOURCE = "source",
  KEY = "key",
  VALUE = "value",
  REASON = "reason",
  CREATED_BY = "created_by",
  TYPE = "type",
}

export const DEFAULT_SELECTED_COLUMNS = [
  FeedbackScoreTableColumns.VALUE,
  FeedbackScoreTableColumns.REASON,
];

/**
 * Default columns for aggregated span scores at trace level.
 * Includes Type column to show span types (LLM, Tool, etc.)
 */
export const DEFAULT_SELECTED_COLUMNS_WITH_TYPE = [
  FeedbackScoreTableColumns.TYPE,
  FeedbackScoreTableColumns.VALUE,
  FeedbackScoreTableColumns.CREATED_BY,
];

/**
 * Filters out Type column from configurable columns.
 * Used when Type column should not be available (trace scores, individual span scores).
 */
export const getConfigurableColumnsWithoutType = () =>
  CONFIGURABLE_COLUMNS.filter(
    (col) => col.id !== FeedbackScoreTableColumns.TYPE,
  );

/**
 * Gets the storage key type based on entityType and isAggregatedSpanScores.
 * Aggregated span scores use "span" storage, otherwise use entityType.
 */
export const getStorageKeyType = (
  entityType: string,
  isAggregatedSpanScores: boolean,
): string => {
  return isAggregatedSpanScores ? "span" : entityType;
};

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

// Map entity types to localStorage keys
// "trace" = trace scores table
// "span" = span scores table (when shown at trace level as aggregated scores)
export const ENTITY_TYPE_TO_STORAGE_KEYS: Record<
  string,
  EntityTypeStorageValues
> = {
  trace: {
    selectedColumns: `trace-${SELECTED_COLUMNS_KEY}`,
    columnsOrder: `trace-${COLUMNS_ORDER_KEY}`,
    columnSizing: `trace-${COLUMN_SIZING_KEY}`,
  },
  span: {
    selectedColumns: `span-${SELECTED_COLUMNS_KEY}`,
    columnsOrder: `span-${COLUMNS_ORDER_KEY}`,
    columnSizing: `span-${COLUMN_SIZING_KEY}`,
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
    id: FeedbackScoreTableColumns.TYPE,
    label: "Type",
    type: COLUMN_TYPE.string,
    size: 100,
    cell: TypeCell as never,
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
