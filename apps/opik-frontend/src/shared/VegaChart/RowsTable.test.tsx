import React from "react";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import RowsTable from "./RowsTable";

const ROWS = [
  { model: "gpt-4o", cost: "9.5" },
  { model: "claude", cost: "10" },
  { model: "llama", cost: null },
  { model: "mistral", cost: "2" },
];

const columnValues = (column: number) =>
  screen
    .getAllByRole("row")
    .slice(1)
    .map((row) => row.querySelectorAll("td")[column].textContent);

const header = (name: string) => screen.getByText(name).closest("th")!;

describe("RowsTable", () => {
  it("sorts numbers as numbers and keeps empty cells last", () => {
    render(<RowsTable rows={ROWS} columns={["model", "cost"]} />);
    expect(columnValues(0)).toEqual(["gpt-4o", "claude", "llama", "mistral"]);

    fireEvent.click(screen.getByText("cost"));
    expect(columnValues(1)).toEqual(["10", "9.5", "2", "—"]);

    fireEvent.click(screen.getByText("cost"));
    expect(columnValues(1)).toEqual(["2", "9.5", "10", "—"]);
  });

  it("sorts text columns alphabetically", () => {
    render(<RowsTable rows={ROWS} columns={["model", "cost"]} />);

    fireEvent.click(screen.getByText("model"));
    expect(columnValues(0)).toEqual(["claude", "gpt-4o", "llama", "mistral"]);
  });

  it("starts from a saved sort and reports changes", () => {
    const onSortingChange = vi.fn();
    render(
      <RowsTable
        rows={ROWS}
        columns={["model", "cost"]}
        sorting={[{ id: "cost", desc: true }]}
        onSortingChange={onSortingChange}
      />,
    );
    expect(columnValues(1)).toEqual(["10", "9.5", "2", "—"]);

    fireEvent.click(screen.getByText("cost"));
    expect(onSortingChange).toHaveBeenCalledWith([{ id: "cost", desc: false }]);
  });

  it("ignores a saved sort on a column the rows no longer have", () => {
    render(
      <RowsTable
        rows={ROWS}
        columns={["model", "cost"]}
        sorting={[{ id: "latency", desc: true }]}
      />,
    );
    expect(columnValues(0)).toEqual(["gpt-4o", "claude", "llama", "mistral"]);
  });

  it("resizes a column by dragging its border and reports the width once", async () => {
    const onColumnsWidthChange = vi.fn();
    render(
      <RowsTable
        rows={ROWS}
        columns={["model", "cost"]}
        columnsWidth={{ model: 200 }}
        onColumnsWidthChange={onColumnsWidthChange}
      />,
    );
    const resizer = header("model").querySelector(".cursor-ew-resize")!;

    fireEvent.mouseDown(resizer, { clientX: 100 });
    fireEvent.mouseMove(document, { clientX: 130 });
    fireEvent.mouseMove(document, { clientX: 160 });
    fireEvent.mouseUp(document, { clientX: 160 });

    await waitFor(() =>
      expect(onColumnsWidthChange).toHaveBeenCalledWith({ model: 260 }),
    );
    expect(onColumnsWidthChange).toHaveBeenCalledTimes(1);
  });
});
