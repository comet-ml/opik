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
  Coins,
} from "lucide-react";
import HeaderWrapper from "@/components/shared/DataTableHeaders/HeaderWrapper";
import useSortableHeader from "@/components/shared/DataTableHeaders/useSortableHeader";

const COLUMN_TYPE_MAP: Record<
  COLUMN_TYPE,
  ForwardRefExoticComponent<
    Omit<LucideProps, "ref"> & RefAttributes<SVGSVGElement>
  >
> = {
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
  const { header, type: columnType, iconType } = column.columnDef.meta ?? {};
  const type = iconType ?? columnType;
  const Icon = type ? COLUMN_TYPE_MAP[type] : "span";

  const { className, onClickHandler, renderSort } = useSortableHeader({
    column,
  });

  return (
    <HeaderWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
      className={className}
      onClick={onClickHandler}
    >
      {Boolean(Icon) && <Icon className="size-3.5 shrink-0 text-slate-300" />}
      <span className="truncate">{header}</span>
      {renderSort()}
    </HeaderWrapper>
  );
};

export default TypeHeader;
