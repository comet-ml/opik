import { describe, expect, it, vi } from "vitest";
import { render, renderHook } from "@testing-library/react";
import { CellContext } from "@tanstack/react-table";

import { useExperimentsTableConfig } from "./useExperimentsTableConfig";
import DurationCell from "@/shared/DataTableCells/DurationCell";
import { COLUMN_DATASET_ID, COLUMN_NAME_ID, COLUMN_TYPE } from "@/types/shared";
import { SORT_DIRECTION } from "@/types/sorting";

type Row = { id: string; duration?: number; trace_count?: number };

const renderColumns = () =>
  renderHook(() =>
    useExperimentsTableConfig<Row>({
      storageKeyPrefix: "test-experiments-table",
      defaultColumns: [
        { id: COLUMN_NAME_ID, label: "Name", type: COLUMN_TYPE.string },
        {
          id: "duration",
          label: "Duration",
          type: COLUMN_TYPE.duration,
          aggregatedCell: DurationCell.Aggregation as never,
          customMeta: { aggregationKey: "duration" },
        },
        { id: "trace_count", label: "Trace count", type: COLUMN_TYPE.number },
      ],
      defaultSelectedColumns: ["duration", "trace_count"],
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

const findColumn = (id: string) =>
  renderColumns().find(
    (c) => (c as { accessorKey?: string }).accessorKey === id,
  );

const groupRowContext = (aggregation: Record<string, unknown>, meta: unknown) =>
  ({
    getValue: () => 2.423,
    row: { id: "group-row" },
    column: { columnDef: { meta } },
    table: {
      options: { meta: { aggregationMap: { "group-row": aggregation } } },
    },
  }) as unknown as CellContext<Row, unknown>;

describe("useExperimentsTableConfig", () => {
  it("keeps a column's own group stat cell", () => {
    expect(findColumn("duration")?.aggregatedCell).toBe(
      DurationCell.Aggregation,
    );
  });

  it("renders nothing in group rows for columns without a group stat", () => {
    const render = findColumn("trace_count")?.aggregatedCell as (
      context: CellContext<Row, unknown>,
    ) => unknown;

    expect(render(groupRowContext({}, undefined))).toBeNull();
  });
});

describe("group row stat cells", () => {
  const meta = { custom: { aggregationKey: "duration" } };

  it("shows the group stat as muted text", () => {
    const { container } = render(
      <DurationCell.Aggregation
        {...(groupRowContext(
          { duration: 1200 },
          meta,
        ) as unknown as CellContext<Row, string>)}
      />,
    );

    const value = container.querySelector(".text-light-slate");
    expect(value?.textContent).toBe("1.2s");
  });

  it("leaves the cell blank instead of a dash when there is no value", () => {
    const { container } = render(
      <DurationCell.Aggregation
        {...(groupRowContext({}, meta) as unknown as CellContext<Row, string>)}
      />,
    );

    expect(container.textContent).toBe("");
  });
});
