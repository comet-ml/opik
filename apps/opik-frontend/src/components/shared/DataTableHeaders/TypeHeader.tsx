import React, { ForwardRefExoticComponent, RefAttributes } from "react";
import { HeaderContext } from "@tanstack/react-table";
import { COLUMN_TYPE } from "@/types/shared";
import {
  Text,
  Hash,
  LucideProps,
  List,
  Clock,
  Braces,
  PenLine,
  ArrowDown,
  ArrowUp,
} from "lucide-react";
import { cn } from "@/lib/utils";

const COLUMN_TYPE_MAP: Record<
  string,
  ForwardRefExoticComponent<
    Omit<LucideProps, "ref"> & RefAttributes<SVGSVGElement>
  >
> = {
  [COLUMN_TYPE.string]: Text,
  [COLUMN_TYPE.number]: Hash,
  [COLUMN_TYPE.list]: List,
  [COLUMN_TYPE.time]: Clock,
  [COLUMN_TYPE.dictionary]: Braces,
  [COLUMN_TYPE.numberDictionary]: PenLine,
};

export const TypeHeader = <TData,>({
  column,
}: HeaderContext<TData, unknown>) => {
  const { header, type: columnType, iconType } = column.columnDef.meta ?? {};
  const type = iconType ?? columnType;
  const Icon = type ? COLUMN_TYPE_MAP[type] : "span";
  const isSortable = column.getCanSort();
  const direction = column.getIsSorted();

  const renderSort = () => {
    const nextDirection = column.getNextSortingOrder();

    if (!nextDirection || !isSortable) return null;

    const Icon = direction === "asc" ? ArrowDown : ArrowUp;
    const NextIcon = nextDirection === "asc" ? ArrowDown : ArrowUp;
    return (
      <>
        <Icon
          className={cn(
            "size-3.5 group-hover:hidden shrink-0",
            !direction && "hidden",
          )}
        />
        <NextIcon
          className={cn("hidden size-3.5 group-hover:inline shrink-0")}
        />
      </>
    );
  };

  return (
    <div
      className={cn(
        "flex size-full items-center gap-1 px-2",
        type === COLUMN_TYPE.number && "justify-end",
        isSortable && "cursor-pointer group",
      )}
      onClick={
        isSortable
          ? column.getToggleSortingHandler()
          : (e) => e.stopPropagation()
      }
    >
      {Boolean(Icon) && <Icon className="mr-1 size-4 shrink-0" />}
      <span className="truncate">{header}</span>
      {renderSort()}
    </div>
  );
};
