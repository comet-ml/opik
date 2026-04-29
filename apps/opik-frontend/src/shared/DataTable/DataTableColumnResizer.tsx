import React from "react";
import { Header } from "@tanstack/react-table";
import { cn } from "@/lib/utils";

const EDGE_MARGIN = 5;

type DataTableColumnResizerProps<TData> = {
  header: Header<TData, unknown>;
};

const DataTableColumnResizer = <TData,>({
  header,
}: DataTableColumnResizerProps<TData>) => {
  if (
    !header.column.getCanResize() ||
    (!header.isPlaceholder && header.subHeaders.length > 0)
  )
    return null;

  const resizeHandler = header.getResizeHandler();

  const handleMouseDown = (e: React.MouseEvent) => {
    resizeHandler(e);

    const scrollContainer = (e.target as HTMLElement).closest(
      "[data-table-scroll-container]",
    ) as HTMLElement | null;
    const headerEl = (e.target as HTMLElement).closest("th") as HTMLElement;
    if (!scrollContainer || !headerEl) return;

    const columnId = header.column.id;
    const table = header.getContext().table;

    const onMouseMove = () => {
      const containerRight = scrollContainer.getBoundingClientRect().right;
      const headerLeft = headerEl.getBoundingClientRect().left;
      const maxWidth = containerRight - headerLeft - EDGE_MARGIN;

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

  return (
    <div
      onMouseDown={handleMouseDown}
      onTouchStart={resizeHandler}
      style={{
        userSelect: "none",
        touchAction: "none",
      }}
      className={cn(
        "group absolute top-0 h-[var(--data-table-height,56px)] z-[5] flex cursor-ew-resize items-stretch transition-all",
        header.column.getIsLastColumn()
          ? "right-0 w-1 justify-end"
          : "-right-1 w-[9px] justify-center",
      )}
    >
      <div className="absolute inset-y-0 w-px bg-transparent transition-colors group-hover:bg-gray-600 group-active:bg-blue-600"></div>
    </div>
  );
};

export default DataTableColumnResizer;
