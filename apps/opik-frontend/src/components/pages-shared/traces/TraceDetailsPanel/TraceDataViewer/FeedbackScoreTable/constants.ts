import { COLUMN_TYPE, ColumnData } from "@/types/shared";
import { ExpandingFeedbackScoreRow } from "./types";
import SourceCell from "./cells/SourceCell";
import NameCell from "./cells/NameCell";
import ValueCell from "./cells/ValueCell";
import ReasonCell from "./cells/ReasonCell";
import AuthorCell from "./cells/AuthorCell";

export enum FeedbackScoreTableColumns {
  SOURCE = "source",
  NAME = "name",
  VALUE = "value",
  REASON = "reason",
  CREATED_BY = "created_by",
}

export const DEFAULT_SELECTED_COLUMNS = [
  FeedbackScoreTableColumns.NAME,
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

export const DEFAULT_COLUMNS: ColumnData<ExpandingFeedbackScoreRow>[] = [
  {
    id: FeedbackScoreTableColumns.NAME,
    label: "Key",
    type: COLUMN_TYPE.string,
    size: 100,
    cell: NameCell as never,
  },
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

export const CONFIGURABLE_COLUMNS: ColumnData<ExpandingFeedbackScoreRow>[] =
  DEFAULT_COLUMNS.filter(
    (column) => column.id !== FeedbackScoreTableColumns.NAME,
  );
