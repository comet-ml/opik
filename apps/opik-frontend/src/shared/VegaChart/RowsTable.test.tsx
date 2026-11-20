import React from "react";
import {
  act,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { getTextWidth } from "@/lib/utils";
import RowsTable from "./RowsTable";

// happy-dom has no canvas: 8px per character, 14px per wide (CJK) one.
const { measureByChars } = vi.hoisted(() => ({
  measureByChars: (texts: string[]) =>
    texts.map((text) =>
      [...text].reduce(
        (width, char) => width + (/[\u3000-\u9fff]/.test(char) ? 14 : 8),
        0,
      ),
    ),
}));
vi.mock("@/lib/utils", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/lib/utils")>()),
  getTextWidth: vi.fn(measureByChars),
}));

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
    vi.mocked(getTextWidth).mockImplementation(measureByChars);
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

  describe("column widths", () => {
    // "model" needs 40 * 8 + 24 = 344px for its longest value; "cost" fits the 80px minimum.
    const WIDE_ROWS = [
      { model: "m".repeat(40), cost: "1" },
      { model: "small", cost: "22" },
    ];

    // happy-dom never resizes: this observer lets a test report a new widget width.
    let callbacks: Array<() => void> = [];
    const resize = () => callbacks.forEach((callback) => callback());
    class TestResizeObserver {
      constructor(callback: () => void) {
        callbacks.push(callback);
      }
      observe() {}
      disconnect() {}
    }

    const scrollFrame = () =>
      document.querySelector<HTMLElement>("[data-table-scroll-container]")!;

    const setWidgetWidth = async (width: number) => {
      const frame = scrollFrame().parentElement!.parentElement!;
      Object.defineProperty(frame, "clientWidth", {
        configurable: true,
        value: width,
      });
      act(() => resize());
      await act(() => new Promise(requestAnimationFrame));
    };

    const shownWidths = () =>
      Array.from(document.querySelectorAll("col")).map(
        (col) => col.style.width,
      );

    const drag = (column: string, from: number, to: number) => {
      // The resizer's own limit reads the frame's edge, which has no layout here.
      scrollFrame().getBoundingClientRect = () => ({ right: 10000 }) as DOMRect;
      const resizer = header(column).querySelector(".cursor-ew-resize")!;
      fireEvent.mouseDown(resizer, { clientX: from });
      fireEvent.mouseMove(document, { clientX: (from + to) / 2 });
      fireEvent.mouseMove(document, { clientX: to });
      fireEvent.mouseUp(document, { clientX: to });
    };

    beforeEach(() => {
      vi.stubGlobal("ResizeObserver", TestResizeObserver);
    });

    afterEach(() => {
      callbacks = [];
      vi.unstubAllGlobals();
    });

    it("sizes columns to their content by default", () => {
      render(<RowsTable rows={WIDE_ROWS} columns={["model", "cost"]} />);
      expect(shownWidths()).toEqual(["344px", "80px"]);
    });

    it("sizes a column to its measured text, wide characters included", () => {
      render(<RowsTable rows={[{ 用户满意度评分: "高" }]} />);
      // 7 * 14 + 24 + 18 = 140px; a flat 8px per character cut it at 98px.
      expect(shownWidths()).toEqual(["140px"]);
    });

    it("falls back to 8px a character without a canvas", () => {
      vi.mocked(getTextWidth).mockImplementation(() => {
        throw new TypeError("no canvas");
      });
      render(<RowsTable rows={[{ 用户满意度评分: "高" }]} />);
      // 7 * 8 + 24 + 18: the old estimate, which under-measures CJK.
      expect(shownWidths()).toEqual(["98px"]);
    });

    it("sizes a column named like an object property", () => {
      render(
        <RowsTable
          rows={[{ constructor: "small", cost: "1" }]}
          columns={["constructor", "cost"]}
          columnsWidth={{ cost: 90 }}
        />,
      );
      expect(shownWidths()).toEqual(["130px", "80px"]);
    });

    it("shares a narrow widget, keeping narrow columns whole", async () => {
      render(<RowsTable rows={WIDE_ROWS} columns={["model", "cost"]} />);

      await setWidgetWidth(300);
      expect(shownWidths()).toEqual(["220px", "80px"]);
    });

    it("scrolls only when the widget is narrower than the minimum widths", async () => {
      render(<RowsTable rows={WIDE_ROWS} columns={["model", "cost"]} />);

      await setWidgetWidth(100);
      expect(shownWidths()).toEqual(["80px", "80px"]);
    });

    it("draws a saved width no wider than the widget or the content", async () => {
      render(
        <RowsTable
          rows={WIDE_ROWS}
          columns={["model", "cost"]}
          columnsWidth={{ model: 2000 }}
        />,
      );

      await setWidgetWidth(300);
      expect(shownWidths()).toEqual(["300px", "80px"]);

      await setWidgetWidth(3000);
      expect(shownWidths()).toEqual(["344px", "80px"]);
    });

    it("resizes a column by dragging its border and reports the width once", async () => {
      const onColumnsWidthChange = vi.fn();
      render(
        <RowsTable
          rows={WIDE_ROWS}
          columns={["model", "cost"]}
          columnsWidth={{ model: 200 }}
          onColumnsWidthChange={onColumnsWidthChange}
        />,
      );

      drag("model", 100, 160);

      await waitFor(() =>
        expect(onColumnsWidthChange).toHaveBeenCalledWith({ model: 260 }),
      );
      expect(onColumnsWidthChange).toHaveBeenCalledTimes(1);
    });

    it("saves a drag capped at the content and keeps the other saved widths", async () => {
      const onColumnsWidthChange = vi.fn();
      render(
        <RowsTable
          rows={WIDE_ROWS}
          columns={["model", "cost"]}
          columnsWidth={{ model: 200, cost: 2000 }}
          onColumnsWidthChange={onColumnsWidthChange}
        />,
      );
      await setWidgetWidth(1000);

      drag("model", 100, 1100);

      await waitFor(() =>
        expect(onColumnsWidthChange).toHaveBeenCalledWith({
          model: 344,
          cost: 2000,
        }),
      );
    });

    it("saves a drag capped at the widget width", async () => {
      const onColumnsWidthChange = vi.fn();
      render(
        <RowsTable
          rows={WIDE_ROWS}
          columns={["model", "cost"]}
          columnsWidth={{ model: 100 }}
          onColumnsWidthChange={onColumnsWidthChange}
        />,
      );
      await setWidgetWidth(250);

      drag("model", 100, 1100);

      await waitFor(() =>
        expect(onColumnsWidthChange).toHaveBeenCalledWith({ model: 250 }),
      );
    });
  });

  it("does not let a drag shrink a column below the minimum width", async () => {
    const onColumnsWidthChange = vi.fn();
    render(
      <RowsTable
        rows={ROWS}
        columns={["model", "cost"]}
        columnsWidth={{ model: 100 }}
        onColumnsWidthChange={onColumnsWidthChange}
      />,
    );
    document.querySelector<HTMLElement>(
      "[data-table-scroll-container]",
    )!.getBoundingClientRect = () => ({ right: 10000 }) as DOMRect;
    const resizer = header("model").querySelector(".cursor-ew-resize")!;

    fireEvent.mouseDown(resizer, { clientX: 500 });
    fireEvent.mouseMove(document, { clientX: 0 });
    fireEvent.mouseUp(document, { clientX: 0 });

    await waitFor(() =>
      expect(onColumnsWidthChange).toHaveBeenCalledWith({ model: 80 }),
    );
  });

  it("ignores a saved width below the minimum", () => {
    render(
      <RowsTable
        rows={ROWS}
        columns={["model", "cost"]}
        columnsWidth={{ model: 0 }}
      />,
    );
    expect(
      Array.from(document.querySelectorAll("col")).map(
        (col) => col.style.width,
      ),
    ).toEqual(["80px", "80px"]);
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
