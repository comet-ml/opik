import { describe, expect, it, vi } from "vitest";
import { render, renderHook } from "@testing-library/react";
import { CellContext, Row as TableRow } from "@tanstack/react-table";

import { useExperimentsTableConfig } from "./useExperimentsTableConfig";
import CostCell from "@/shared/DataTableCells/CostCell";
import DurationCell from "@/shared/DataTableCells/DurationCell";
import PassRateCell from "@/shared/DataTableCells/PassRateCell";
import { COLUMN_DATASET_ID, COLUMN_NAME_ID, COLUMN_TYPE } from "@/types/shared";
import { SORT_DIRECTION } from "@/types/sorting";

// Radix tooltips only mount their content on hover, so render it inline.
vi.mock("@/shared/TooltipWrapper/TooltipWrapper", () => ({
  default: ({
    content,
    children,
  }: {
    content?: React.ReactNode;
    children: React.ReactNode;
  }) => (
    <>
      {children}
      {content && <div data-testid="tooltip">{content}</div>}
    </>
  ),
}));

type Row = { id: string; duration?: number; trace_count?: number };

const renderConfig = () =>
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
  ).result.current;

const renderColumns = () => renderConfig().columns;

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

describe("group row background", () => {
  it("marks group rows only", () => {
    const { getExperimentRowClassName } = renderConfig();

    expect(
      getExperimentRowClassName({ getIsGrouped: () => true } as TableRow<Row>),
    ).toBe("comet-table-row-group");
    expect(
      getExperimentRowClassName({ getIsGrouped: () => false } as TableRow<Row>),
    ).toBe("");
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

  it("leaves a zero group cost blank instead of a dash", () => {
    const { container } = render(
      <CostCell.Aggregation
        {...(groupRowContext(
          { cost: 0 },
          { custom: { aggregationKey: "cost" } },
        ) as unknown as CellContext<Row, string>)}
      />,
    );

    expect(container.textContent).toBe("");
  });
});

describe("group row pass rate", () => {
  const meta = { custom: { aggregationKey: "pass_rate_avg" } };

  const renderPassRate = (aggregation: Record<string, unknown>) =>
    render(
      <PassRateCell.Aggregation
        {...(groupRowContext(aggregation, meta) as unknown as CellContext<
          Row,
          unknown
        >)}
      />,
    );

  it("shows a pass rate below 100% as the red status tag", () => {
    const { getByText } = renderPassRate({
      pass_rate_avg: 0.8,
      passed_count_sum: 12,
      total_count_sum: 15,
    });

    expect(getByText("80%").closest("[class*='--tag-red-bg']")).not.toBeNull();
  });

  it("shows a 100% pass rate as the green status tag", () => {
    const { getByText } = renderPassRate({
      pass_rate_avg: 1,
      passed_count_sum: 15,
      total_count_sum: 15,
    });

    expect(
      getByText("100%").closest("[class*='--tag-green-bg']"),
    ).not.toBeNull();
  });

  it("explains the average and the pooled item counts in the tooltip", () => {
    const { getByTestId } = renderPassRate({
      pass_rate_avg: 0.5,
      passed_count_sum: 1,
      total_count_sum: 10,
    });

    const tooltip = getByTestId("tooltip");
    expect(tooltip.textContent).toContain(
      "Average of the experiments' pass rates",
    );
    expect(tooltip.textContent).toContain("1 / 10 items passed in total");
  });

  it("leaves the cell blank for a group without test-suite experiments", () => {
    const { container } = renderPassRate({ experiment_count: 3 });

    expect(container.textContent).toBe("");
  });
});
