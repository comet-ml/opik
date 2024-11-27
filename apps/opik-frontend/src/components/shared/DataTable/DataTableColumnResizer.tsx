import { Header } from "@tanstack/react-table";
import React from "react";
import { cn } from "@/lib/utils";

type DataTableColumnResizerProps<TData> = {
  header: Header<TData, unknown>;
};

const DataTableColumnResizer = <TData,>({
  header,
}: DataTableColumnResizerProps<TData>) => {
  if (!header.column.getCanResize()) return <></>;
  const hasStatistic = Boolean(
    header.getContext().table.options.meta?.columnsStatistic,
  );

  return (
    <div
      {...{
        onMouseDown: header.getResizeHandler(),
        onTouchStart: header.getResizeHandler(),
        style: {
          userSelect: "none",
          touchAction: "none",
        },
      }}
      className={cn(
        "group absolute top-0 z-[5] flex h-[10000px] w-2 cursor-ew-resize items-stretch justify-center transition-all",
        header.column.getIsLastColumn() ? "right-0" : "-right-1",
      )}
    >
      <div
        className={cn(
          "absolute top-2 h-7 w-px bg-border",
          hasStatistic && "h-8",
        )}
      ></div>
      <div className="absolute inset-y-0 w-px bg-transparent transition-colors group-hover:bg-gray-600 group-active:bg-blue-600"></div>
    </div>
  );
};

export default DataTableColumnResizer;
