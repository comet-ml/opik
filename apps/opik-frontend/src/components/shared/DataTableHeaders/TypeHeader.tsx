import React from "react";
import { HeaderContext } from "@tanstack/react-table";
import { COLUMN_TYPE, HeaderIcon } from "@/types/shared";
import {
  Text,
  Hash,
  List,
  Clock,
  Braces,
  PenLine,
  ArrowDown,
  ArrowUp,
  Coins,
} from "lucide-react";
import { cn } from "@/lib/utils";
import HeaderWrapper from "@/components/shared/DataTableHeaders/HeaderWrapper";

const COLUMN_TYPE_MAP: Record<COLUMN_TYPE, HeaderIcon> = {
  [COLUMN_TYPE.string]: Text,
  [COLUMN_TYPE.number]: Hash,
  [COLUMN_TYPE.list]: List,
  [COLUMN_TYPE.time]: Clock,
  [COLUMN_TYPE.duration]: Clock,
  [COLUMN_TYPE.dictionary]: Braces,
  [COLUMN_TYPE.numberDictionary]: PenLine,
  [COLUMN_TYPE.cost]: Coins,
};

const TypeHeader = <TData,>(context: HeaderContext<TData, unknown>) => {
  const { column } = context;
  const {
    header,
    type: columnType,
    iconType,
    HeaderIcon,
  } = column.columnDef.meta ?? {};

  const type = iconType ?? columnType;
  const CoreIcon = type ? COLUMN_TYPE_MAP[type] : "span";
  const Icon = HeaderIcon || CoreIcon;
  const isSortable = column.getCanSort();
  const direction = column.getIsSorted();

  const renderSort = () => {
    const nextDirection = column.getNextSortingOrder();

    if (!direction && !nextDirection) return null;

    const Icon = (direction || nextDirection) === "asc" ? ArrowUp : ArrowDown;
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
    <HeaderWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
      className={cn(isSortable && "cursor-pointer group")}
      onClick={
        isSortable
          ? column.getToggleSortingHandler()
          : (e) => e.stopPropagation()
      }
    >
      {Boolean(Icon) && <Icon className="size-3.5 shrink-0 text-slate-300" />}
      <span className="truncate">{header}</span>
      {isSortable && renderSort()}
    </HeaderWrapper>
  );
};

export default TypeHeader;
