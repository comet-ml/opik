import { describe, expect, it, vi } from "vitest";
import { fireEvent, render, renderHook, screen } from "@testing-library/react";
import { CellContext, Row as TableRow } from "@tanstack/react-table";

import { useExperimentsTableConfig } from "./useExperimentsTableConfig";
import CostCell from "@/shared/DataTableCells/CostCell";
import DurationCell from "@/shared/DataTableCells/DurationCell";
import ResourceCell from "@/shared/DataTableCells/ResourceCell";
import { COLUMN_DATASET_ID, COLUMN_NAME_ID, COLUMN_TYPE } from "@/types/shared";
import { SORT_DIRECTION } from "@/types/sorting";

vi.mock("@tanstack/react-router", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@tanstack/react-router")>()),
  Link: ({ children }: { children: React.ReactNode }) => (
    <a data-testid="link">{children}</a>
  ),
}));

vi.mock("@/store/AppStore", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/store/AppStore")>()),
  default: (selector: (state: { activeWorkspaceName: string }) => unknown) =>
    selector({ activeWorkspaceName: "ws" }),
  useActiveProjectId: () => "active-project",
}));

type Row = {
  id: string;
  name?: string;
  dataset_id?: string;
  duration?: number;
  trace_count?: number;
};

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

describe("grouped experiment name", () => {
  it("renders the name as plain text inside the link, without an underline", () => {
    const nameColumn = findColumn(COLUMN_NAME_ID);
    const context = {
      row: {
        original: { id: "exp-1", name: "my experiment", dataset_id: "ds-1" },
      },
      column: { columnDef: { meta: nameColumn?.meta } },
      table: { options: { meta: {} } },
    } as unknown as CellContext<Row, unknown>;

    render(<ResourceCell {...context} />);

    const name = screen.getByText("my experiment");
    expect(name).not.toHaveClass("underline");
    expect(screen.getByTestId("link")).toContainElement(name);
  });

  describe("cell clicks", () => {
    const renderNameCell = (onRowClick: () => void) => {
      const nameColumn = findColumn(COLUMN_NAME_ID);
      const context = {
        row: {
          id: "exp-1",
          depth: 1,
          original: { id: "exp-1", name: "my experiment", dataset_id: "ds-1" },
          getIsSelected: () => false,
          getCanSelect: () => true,
          toggleSelected: vi.fn(),
        },
        column: { columnDef: { meta: nameColumn?.meta } },
        table: { options: { meta: {} } },
      } as unknown as CellContext<Row, unknown>;
      (context as unknown as { cell: unknown }).cell = {
        getContext: () => context,
      };

      const renderCell = nameColumn?.cell as (
        context: CellContext<Row, unknown>,
      ) => React.ReactNode;

      return render(<div onClick={onRowClick}>{renderCell(context)}</div>);
    };

    it("lets clicks on the empty part of the name cell reach the row", () => {
      const onRowClick = vi.fn();
      const { container } = renderNameCell(onRowClick);

      fireEvent.click(container.firstElementChild!.firstElementChild!);

      expect(onRowClick).toHaveBeenCalledTimes(1);
    });

    it("keeps checkbox clicks from opening the experiment", () => {
      const onRowClick = vi.fn();
      renderNameCell(onRowClick);

      fireEvent.click(screen.getByRole("checkbox"));

      expect(onRowClick).not.toHaveBeenCalled();
    });
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
