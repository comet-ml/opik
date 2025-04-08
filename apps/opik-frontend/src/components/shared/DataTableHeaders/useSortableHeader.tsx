import React from "react";
import { ArrowDown, ArrowUp } from "lucide-react";
import { Column } from "@tanstack/react-table";
import { cn } from "@/lib/utils";

type UseSortableHeaderProps<TData> = {
  column: Column<TData>;
};

export const useSortableHeader = <TData,>({
  column,
}: UseSortableHeaderProps<TData>) => {
  const isSortable = column.getCanSort();
  const direction = column.getIsSorted();

  const renderSort = () => {
    if (!isSortable) return null;

    const nextDirection = column.getNextSortingOrder();

    if (!direction && !nextDirection) return null;

    const Icon = (direction || nextDirection) === "asc" ? ArrowUp : ArrowDown;
    return (
      <>
        <Icon
          className={cn(
            "shrink-0 hidden size-3.5 group-hover:inline",
            direction && "inline",
          )}
        />
      </>
    );
  };

  return {
    renderSort,
    className: isSortable ? "cursor-pointer group" : undefined,
    onClickHandler: isSortable
      ? column.getToggleSortingHandler()
      : (e: React.MouseEvent<unknown>) => e.stopPropagation(),
  };
};

export default useSortableHeader;
