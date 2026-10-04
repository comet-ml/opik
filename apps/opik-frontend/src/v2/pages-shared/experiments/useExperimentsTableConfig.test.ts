import { describe, it, expect, beforeEach } from "vitest";
import { renderHook } from "@testing-library/react";

import {
  COLUMN_ACTIONS_ID,
  COLUMN_CREATED_AT_ID,
  COLUMN_DATASET_ID,
  COLUMN_NAME_ID,
  COLUMN_TYPE,
  ColumnData,
  DynamicColumn,
} from "@/types/shared";
import { Groups } from "@/types/groups";
import { buildGroupFieldName } from "@/lib/groups";
import { buildScoreColumnId } from "@/lib/feedback-scores";
import { SORT_DIRECTION } from "@/types/sorting";
import { useExperimentsTableConfig } from "./useExperimentsTableConfig";

type Row = { id: string };

const COLUMNS: ColumnData<Row>[] = [
  { id: COLUMN_NAME_ID, label: "Name", type: COLUMN_TYPE.string },
  { id: COLUMN_DATASET_ID, label: "Dataset", type: COLUMN_TYPE.string },
  { id: COLUMN_CREATED_AT_ID, label: "Created", type: COLUMN_TYPE.time },
];

const DATASET_GROUP: Groups = [
  {
    id: "g1",
    field: COLUMN_DATASET_ID,
    direction: SORT_DIRECTION.ASC,
    type: COLUMN_TYPE.string,
  },
];

const SCORE_COLUMN_ID = buildScoreColumnId("accuracy");

const SCORE_COLUMNS: DynamicColumn[] = [
  { id: SCORE_COLUMN_ID, label: "accuracy", columnType: COLUMN_TYPE.number },
];

const renderConfig = (
  selectedColumns: string[],
  groups: Groups = [],
  { withActions = false } = {},
) =>
  renderHook(() =>
    useExperimentsTableConfig<Row>({
      storageKeyPrefix: "test",
      defaultColumns: COLUMNS,
      defaultSelectedColumns: selectedColumns,
      defaultColumnsOrder: COLUMNS.map((c) => c.id),
      groups,
      sortableBy: [],
      dynamicScoresColumns: SCORE_COLUMNS,
      experiments: [],
      rowSelection: {},
      sortedColumns: [],
      setSortedColumns: () => {},
      ...(withActions && { actionsCell: () => null }),
    }),
  );

describe("useExperimentsTableConfig fillColumnId", () => {
  beforeEach(() => {
    localStorage.clear();
  });

  it("uses the name column when ungrouped", () => {
    const { result } = renderConfig([COLUMN_NAME_ID, COLUMN_DATASET_ID]);

    expect(result.current.fillColumnId).toBe(COLUMN_NAME_ID);
  });

  it("skips the pinned name and group columns when grouped", () => {
    const { result } = renderConfig(
      [COLUMN_NAME_ID, COLUMN_CREATED_AT_ID],
      DATASET_GROUP,
    );

    expect(result.current.columnPinningConfig.left).toEqual(
      expect.arrayContaining([
        COLUMN_NAME_ID,
        buildGroupFieldName(DATASET_GROUP[0]),
      ]),
    );
    expect(result.current.fillColumnId).toBe(COLUMN_CREATED_AT_ID);
  });

  it("uses the TanStack-normalized score column id when only scores are visible", () => {
    const { result } = renderConfig(
      [COLUMN_NAME_ID, SCORE_COLUMN_ID],
      DATASET_GROUP,
    );

    expect(result.current.fillColumnId).toBe("feedback_scores_accuracy");
  });

  it("falls back to the actions column when no data column is visible", () => {
    const { result } = renderConfig([COLUMN_NAME_ID], DATASET_GROUP, {
      withActions: true,
    });

    expect(result.current.fillColumnId).toBe(COLUMN_ACTIONS_ID);
  });

  it("has no fill column when no unpinned column is visible", () => {
    const { result } = renderConfig([COLUMN_NAME_ID], DATASET_GROUP);

    expect(result.current.fillColumnId).toBeUndefined();
  });
});
