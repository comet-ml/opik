import React from "react";
import { HeaderContext } from "@tanstack/react-table";

const SectionHeader = <TData,>(context: HeaderContext<TData, unknown>) => {
  const { column } = context;
  const { header } = column.columnDef.meta ?? {};

  return (
    <div className="comet-body-xs-accented relative h-4 px-3 pt-3 text-[hsl(var(--light-slate)/0.60)]">
      <div className="truncate">{header}</div>
    </div>
  );
};

export default SectionHeader;
