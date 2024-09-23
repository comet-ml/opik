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

  return (
    <div
      className={cn(
        "flex size-full items-center gap-2 px-2",
        type === COLUMN_TYPE.number && "justify-end",
      )}
      onClick={(e) => e.stopPropagation()}
    >
      {Boolean(Icon) && <Icon className="size-4 shrink-0" />}
      <span className="truncate">{header}</span>
    </div>
  );
};
