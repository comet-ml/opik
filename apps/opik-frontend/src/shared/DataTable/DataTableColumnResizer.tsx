import React from "react";
import { Header } from "@tanstack/react-table";
import { cn } from "@/lib/utils";

const EDGE_MARGIN = 5;

type DataTableColumnResizerProps<TData> = {
  header: Header<TData, unknown>;
};

export const getCanResizeHeader = <TData,>(header: Header<TData, unknown>) =>
  header.column.getCanResize() &&
  (header.isPlaceholder || header.subHeaders.length === 0);

const getIsLastColumn = <TData,>(header: Header<TData, unknown>) => {
  // The overlay column (row actions) is always last and takes no width, so the
  // column before it still ends the table.
  const visibleColumns = header.getContext().table.getVisibleLeafColumns();
  return (
    header.column.getIsLastColumn() ||
    (Boolean(visibleColumns.at(-1)?.columnDef.meta?.overlay) &&
      visibleColumns.at(-2)?.id === header.column.id)
  );
};

const getHandleClassName = (isLastColumn: boolean) =>
  isLastColumn ? "right-0 w-1 justify-end" : "-right-1 w-[9px] justify-center";

const startColumnResize = <TData,>(
  header: Header<TData, unknown>,
  e: React.MouseEvent,
) => {
  header.getResizeHandler()(e);

  const scrollContainer = (e.target as HTMLElement).closest(
    "[data-table-scroll-container]",
  ) as HTMLElement | null;
  const cellEl = (e.target as HTMLElement).closest("th, td") as HTMLElement;
  if (!scrollContainer || !cellEl) return;

  const columnId = header.column.id;
  const table = header.getContext().table;

  const onMouseMove = () => {
    const containerRight = scrollContainer.getBoundingClientRect().right;
    const cellLeft = cellEl.getBoundingClientRect().left;
    const maxWidth = containerRight - cellLeft - EDGE_MARGIN;

    if (header.column.getSize() > maxWidth) {
      table.setColumnSizing((prev) => ({
        ...prev,
        [columnId]: Math.max(
          header.column.columnDef.minSize ?? 0,
          Math.floor(maxWidth),
        ),
      }));
    }
  };

  const cleanup = () => {
    document.removeEventListener("mousemove", onMouseMove);
    document.removeEventListener("mouseup", cleanup);
  };

  document.addEventListener("mousemove", onMouseMove);
  document.addEventListener("mouseup", cleanup);
};

// The header's line spans the whole table; body strips light it up on hover
// by toggling its data attribute directly, so hovering doesn't re-render rows.
const setResizeLineHover = (
  e: React.MouseEvent,
  columnId: string,
  hover: boolean,
) => {
  const line = (e.currentTarget as HTMLElement)
    .closest("table")
    ?.querySelector<HTMLElement>(
      `[data-resize-line="${CSS.escape(columnId)}"]`,
    );
  if (line) line.dataset.hover = String(hover);
};

const DataTableColumnResizer = <TData,>({
  header,
}: DataTableColumnResizerProps<TData>) => {
  if (!getCanResizeHeader(header)) return null;

  const isResizing = header.column.getIsResizing();

  return (
    <div
      onMouseDown={(e) => startColumnResize(header, e)}
      onTouchStart={header.getResizeHandler()}
      style={{
        userSelect: "none",
        touchAction: "none",
      }}
      className={cn(
        "group absolute inset-y-0 z-[5] flex cursor-ew-resize items-stretch",
        getHandleClassName(getIsLastColumn(header)),
      )}
    >
      <div
        data-resize-line={header.column.id}
        className={cn(
          "pointer-events-none absolute top-0 h-[var(--data-table-height,56px)] w-px bg-transparent transition-colors group-hover:bg-gray-600 data-[hover=true]:bg-gray-600",
          isResizing && "!bg-blue-600",
        )}
      ></div>
    </div>
  );
};

type DataTableCellResizerProps<TData> = {
  header: Header<TData, unknown>;
};

// A body cell's share of its column's resize handle. It sits beneath the row
// actions and pinned cells, so they keep their clicks.
export const DataTableCellResizer = <TData,>({
  header,
}: DataTableCellResizerProps<TData>) => (
  <div
    onMouseDown={(e) => startColumnResize(header, e)}
    onTouchStart={header.getResizeHandler()}
    onMouseEnter={(e) => setResizeLineHover(e, header.column.id, true)}
    onMouseLeave={(e) => setResizeLineHover(e, header.column.id, false)}
    onClick={(e) => e.stopPropagation()}
    style={{ userSelect: "none", touchAction: "none" }}
    className={cn(
      "absolute inset-y-0 z-[1] cursor-ew-resize",
      getHandleClassName(getIsLastColumn(header)),
    )}
  />
);

export default DataTableColumnResizer;
