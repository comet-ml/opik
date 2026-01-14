import React from "react";
import { HeaderContext } from "@tanstack/react-table";
import { Trophy } from "lucide-react";

import HeaderWrapper from "@/components/shared/DataTableHeaders/HeaderWrapper";
import useSortableHeader from "@/components/shared/DataTableHeaders/useSortableHeader";

const RankingHeader = <TData,>(context: HeaderContext<TData, unknown>) => {
  const { column } = context;
  const { header } = column.columnDef.meta ?? {};

  const { className, onClickHandler, renderSort } = useSortableHeader({
    column,
    withSeparator: false,
  });

  return (
    <HeaderWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
      className={className}
      onClick={onClickHandler}
    >
      <Trophy className="size-3.5 shrink-0 text-yellow-500" />
      <span className="truncate">{header}</span>
      {renderSort()}
    </HeaderWrapper>
  );
};

export default RankingHeader;
