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
