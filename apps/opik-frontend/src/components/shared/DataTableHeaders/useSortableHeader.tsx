import React from "react";
import { ArrowDown, ArrowUp } from "lucide-react";
import { Column } from "@tanstack/react-table";
import { cn } from "@/lib/utils";
import { Separator } from "@/components/ui/separator";

type UseSortableHeaderProps<TData> = {
  column: Column<TData>;
  withSeparator?: boolean;
};

export const useSortableHeader = <TData,>({
  column,
  withSeparator = false,
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
        {withSeparator && (
          <Separator
            orientation="vertical"
            className={cn(
              "ml-0.5 hidden h-2.5 group-hover:inline",
              direction && "inline",
            )}
          />
        )}
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
    isSortable,
    renderSort,
    className: isSortable ? "cursor-pointer group" : undefined,
    onClickHandler: isSortable
      ? column.getToggleSortingHandler()
      : (e: React.MouseEvent<unknown>) => e.stopPropagation(),
  };
};

export default useSortableHeader;
