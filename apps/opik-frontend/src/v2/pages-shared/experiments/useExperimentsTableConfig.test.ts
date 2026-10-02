import { describe, it, expect, beforeEach } from "vitest";
import { renderHook } from "@testing-library/react";

import {
  COLUMN_CREATED_AT_ID,
  COLUMN_DATASET_ID,
  COLUMN_NAME_ID,
  COLUMN_TYPE,
  ColumnData,
} from "@/types/shared";
import { Groups } from "@/types/groups";
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

const renderConfig = (selectedColumns: string[], groups: Groups = []) =>
  renderHook(() =>
    useExperimentsTableConfig<Row>({
      storageKeyPrefix: "test",
      defaultColumns: COLUMNS,
      defaultSelectedColumns: selectedColumns,
      defaultColumnsOrder: COLUMNS.map((c) => c.id),
      groups,
      sortableBy: [],
      dynamicScoresColumns: [],
      experiments: [],
      rowSelection: {},
      sortedColumns: [],
      setSortedColumns: () => {},
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

    expect(result.current.columnPinningConfig.left).toContain(COLUMN_NAME_ID);
    expect(result.current.fillColumnId).toBe(COLUMN_CREATED_AT_ID);
  });

  it("has no fill column when no unpinned data column is visible", () => {
    const { result } = renderConfig([COLUMN_NAME_ID], DATASET_GROUP);

    expect(result.current.fillColumnId).toBeUndefined();
  });
});
