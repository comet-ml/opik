import { describe, expect, it, vi } from "vitest";
import { act, fireEvent, render } from "@testing-library/react";
import { ColumnDef } from "@tanstack/react-table";

import DataTable from "@/shared/DataTable/DataTable";
import {
  generateActionsColumDef,
  generateSelectColumDef,
} from "@/shared/DataTable/utils";
import { TooltipProvider } from "@/ui/tooltip";

type Row = { id: string; name: string; value: number };

const columns: ColumnDef<Row>[] = [
  generateSelectColumDef<Row>(),
  { accessorKey: "name", header: "Name", size: 300 },
  { accessorKey: "value", header: "Value", size: 100 },
];

const data: Row[] = [{ id: "1", name: "first", value: 1 }];

// The table is table-layout: fixed at width 100%, which spreads spare width over
// every px-sized column. Only data columns may take that spare width, so the
// checkbox column must keep its exact px size.
describe("DataTable column widths", () => {
  it("keeps the select column at its px size and shares the rest by percentage", () => {
    const { container } = render(
      <TooltipProvider>
        <DataTable columns={columns} data={data} getRowId={(r) => r.id} />
      </TooltipProvider>,
    );

    const widths = [...container.querySelectorAll("colgroup col")].map(
      (col) => (col as HTMLElement).style.width,
    );

    expect(widths).toEqual(["40px", "75%", "25%"]);
  });
});

// The row-actions column renders 0px wide; its size becomes a gutter on the last
// data column so the table keeps its width and that column's resize handle stays
// at the table's end.
describe("DataTable row actions overlay column", () => {
  const actionsColumn = generateActionsColumDef<Row>({
    cell: () => <button type="button">act</button>,
  });

  const renderTable = (
    props: Partial<React.ComponentProps<typeof DataTable<Row, unknown>>> = {},
  ) =>
    render(
      <TooltipProvider>
        <DataTable
          columns={[...columns, actionsColumn]}
          data={data}
          getRowId={(r) => r.id}
          {...props}
        />
      </TooltipProvider>,
    );

  it("adds the actions column's size to the last data column", () => {
    const { container } = renderTable();

    const widths = [...container.querySelectorAll("colgroup col")].map(
      (col) => (col as HTMLElement).style.width,
    );

    expect(widths[0]).toBe("40px");
    expect(parseFloat(widths[1])).toBeCloseTo((300 / 444) * 100);
    expect(parseFloat(widths[2])).toBeCloseTo((144 / 444) * 100);
    expect(widths[3]).toBe("0px");
    expect(
      container
        .querySelector("table")
        ?.style.getPropertyValue("--row-actions-gutter"),
    ).toBe("44px");
  });

  it("keeps the actions column rightmost when other columns are pinned right", () => {
    const { container } = renderTable({
      columnPinning: { left: [], right: ["actions", "value"] },
    });

    const headers = [...container.querySelectorAll("thead th")];
    expect(headers.at(-1)?.classList.contains("comet-overlay-cell")).toBe(true);
    expect(headers.at(-2)?.textContent).toBe("Value");
  });

  it("puts the end-of-table resize handle on the column before the actions", () => {
    const { container } = renderTable({ resizeConfig: { enabled: true } });

    const handles = [...container.querySelectorAll("thead th")].map((th) =>
      th.querySelector(".cursor-ew-resize"),
    );
    // Name, Value: only Value (last data column) gets the flush-right handle.
    expect(handles[1]?.classList.contains("right-0")).toBe(false);
    expect(handles[2]?.classList.contains("right-0")).toBe(true);
  });

  it("gives each resizable body cell a resize strip that doesn't open the row", () => {
    vi.useFakeTimers();
    const onRowClick = vi.fn();
    const { container } = renderTable({
      resizeConfig: { enabled: true },
      onRowClick,
    });

    const strips = [...container.querySelectorAll("tbody td")].map((td) =>
      Boolean(td.querySelector(".cursor-ew-resize")),
    );
    // Select, Name, Value, actions: only the data columns resize.
    expect(strips).toEqual([false, true, true, false]);

    fireEvent.click(container.querySelector("tbody .cursor-ew-resize")!);
    act(() => vi.advanceTimersByTime(500));
    expect(onRowClick).not.toHaveBeenCalled();
    vi.useRealTimers();
  });

  it("does not trigger the row click from the actions cell", () => {
    vi.useFakeTimers();
    const onRowClick = vi.fn();
    const { getByRole, getByText } = renderTable({ onRowClick });

    fireEvent.click(getByRole("button", { name: "act" }));
    act(() => vi.advanceTimersByTime(500));
    expect(onRowClick).not.toHaveBeenCalled();

    fireEvent.click(getByText("first"));
    act(() => vi.advanceTimersByTime(500));
    expect(onRowClick).toHaveBeenCalledTimes(1);
    vi.useRealTimers();
  });
});
