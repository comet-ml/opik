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

    if (!isSortable || (!direction && !nextDirection)) return null;

    const Icon = (direction || nextDirection) === "asc" ? ArrowDown : ArrowUp;
    return (
      <>
        <Icon
          className={cn(
            "hidden size-3.5 group-hover:inline",
            direction && "inline",
          )}
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
      {Boolean(Icon) && <Icon className="size-3.5 shrink-0 text-slate-300" />}
      <span className="truncate">{header}</span>
      {renderSort()}
    </div>
  );
};
