import { describe, expect, it, vi } from "vitest";
import { renderHook } from "@testing-library/react";
import { CellContext } from "@tanstack/react-table";

import { useExperimentsTableConfig } from "./useExperimentsTableConfig";
import { COLUMN_DATASET_ID, COLUMN_NAME_ID, COLUMN_TYPE } from "@/types/shared";
import { SORT_DIRECTION } from "@/types/sorting";

type Row = { id: string; duration?: number };

const renderColumns = () =>
  renderHook(() =>
    useExperimentsTableConfig<Row>({
      storageKeyPrefix: "test-experiments-table",
      defaultColumns: [
        { id: COLUMN_NAME_ID, label: "Name", type: COLUMN_TYPE.string },
        { id: "duration", label: "Duration", type: COLUMN_TYPE.duration },
      ],
      defaultSelectedColumns: ["duration"],
      groups: [
        {
          id: "g1",
          field: COLUMN_DATASET_ID,
          type: COLUMN_TYPE.string,
          direction: SORT_DIRECTION.ASC,
          key: "",
        },
      ],
      sortableBy: [],
      dynamicScoresColumns: [],
      experiments: [],
      rowSelection: {},
      sortedColumns: [],
      setSortedColumns: vi.fn(),
    }),
  ).result.current.columns;

describe("useExperimentsTableConfig", () => {
  it("renders nothing in group rows for stat columns", () => {
    const duration = renderColumns().find(
      (c) => (c as { accessorKey?: string }).accessorKey === "duration",
    );

    expect(duration?.aggregatedCell).toBeInstanceOf(Function);
    const render = duration?.aggregatedCell as (
      context: CellContext<Row, unknown>,
    ) => unknown;
    expect(
      render({ getValue: () => 2.423 } as CellContext<Row, unknown>),
    ).toBeNull();
  });
});
