import React from "react";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
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
  afterEach(() => {
    vi.useRealTimers();
  });

  it("sorts numbers as numbers and keeps empty cells last", () => {
    render(<RowsTable rows={ROWS} columns={["model", "cost"]} />);
    expect(columnValues(0)).toEqual(["gpt-4o", "claude", "llama", "mistral"]);

    fireEvent.click(screen.getByText("cost"));
    expect(columnValues(1)).toEqual(["10", "9.5", "2", "-"]);

    fireEvent.click(screen.getByText("cost"));
    expect(columnValues(1)).toEqual(["2", "9.5", "10", "-"]);
  });

  it("keeps text below numbers in either direction", () => {
    render(
      <RowsTable
        rows={[...ROWS, { model: "x", cost: "n/a" }]}
        columns={["model", "cost"]}
      />,
    );

    fireEvent.click(screen.getByText("cost"));
    expect(columnValues(1)).toEqual(["10", "9.5", "2", "n/a", "-"]);

    fireEvent.click(screen.getByText("cost"));
    expect(columnValues(1)).toEqual(["2", "9.5", "10", "n/a", "-"]);
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
    expect(columnValues(1)).toEqual(["10", "9.5", "2", "-"]);

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
    // The resizer stops a column at the frame's right edge, which has no layout here.
    const frame = document.querySelector<HTMLElement>(
      "[data-table-scroll-container]",
    )!;
    frame.getBoundingClientRect = () => ({ right: 1000 }) as DOMRect;
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

  it("does not report widths after it unmounts mid-save", () => {
    vi.useFakeTimers();
    const onColumnsWidthChange = vi.fn();
    const { unmount } = render(
      <RowsTable
        rows={ROWS}
        columns={["model", "cost"]}
        onColumnsWidthChange={onColumnsWidthChange}
      />,
    );
    const resizer = header("model").querySelector(".cursor-ew-resize")!;

    fireEvent.mouseDown(resizer, { clientX: 100 });
    fireEvent.mouseMove(document, { clientX: 130 });
    fireEvent.mouseUp(document, { clientX: 130 });
    unmount();
    vi.runAllTimers();

    expect(onColumnsWidthChange).not.toHaveBeenCalled();
  });
});
