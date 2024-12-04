import React from "react";
import { HeaderContext } from "@tanstack/react-table";

const TypeHeader = <TData,>(context: HeaderContext<TData, unknown>) => {
  const { column } = context;
  const { header } = column.columnDef.meta ?? {};

  return (
    <div className="comet-body-xs-accented relative h-3 px-3 pt-2 text-[rgba(148,163,184,0.60)]">
      {header}
    </div>
  );
};

export default TypeHeader;
